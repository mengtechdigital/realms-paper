package com.realms.data;

import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite-backed realms storage. Mirrors the marriage-paper / cropfarm-paper
 * pattern:
 *   - Direct sqlite-jdbc Driver instantiation (no DriverManager) so two
 *     plugins each bundling sqlite-jdbc don't fight in the classloader.
 *   - WAL journal mode + synchronous=NORMAL.
 *   - Native lib extracted under the plugin data folder so two plugins don't
 *     race on the same temp DLL/SO path.
 *   - Async writer thread with batched-transaction commits.
 *   - In-memory hot maps fronting the DB so all hot-path reads are O(1) on
 *     the main thread.
 *
 * Concurrency: hot-map mutations happen on the main thread (via manager
 * code); the writer thread only persists. Reads from the writer thread are
 * never used to drive game logic — they exist solely to flush the queue.
 */
public final class SqliteRealmsStore implements RealmsStore {

    private static final int BATCH_MAX = 256;
    private static final long POLL_MS = 200L;

    private sealed interface WriteOp {
        record UpsertRealm(Realm realm) implements WriteOp { }
        record DeleteRealm(long id) implements WriteOp { }
        record UpsertResident(Resident r) implements WriteOp { }
        record DeleteResident(UUID uuid) implements WriteOp { }
        record AddClaims(long realmId, Collection<ClaimKey> keys, long millis) implements WriteOp { }
        record RemoveClaim(ClaimKey key) implements WriteOp { }
        record TransferClaim(ClaimKey key, long fromId, long toId, long millis) implements WriteOp { }
        record DeltaPower(ClaimKey key, String material, int delta) implements WriteOp { }
        record ClearLedger(ClaimKey key) implements WriteOp { }
        record SetFlag(long realmId, String name, boolean value) implements WriteOp { }
        record PutRelation(Relation r) implements WriteOp { }
        record RemoveRelation(long a, long b) implements WriteOp { }
        record PutCooldown(long a, long b, String kind, long until) implements WriteOp { }
        record PurgeCooldowns(long cutoff) implements WriteOp { }
        record PutDisplayPrefs(UUID uuid, DisplayPrefs prefs) implements WriteOp { }
    }

    private final Plugin plugin;
    private final File dbFile;

    // ---- Hot caches (main-thread reads, all O(1)) -------------------------
    private final Map<Long, Realm> realmsById = new ConcurrentHashMap<>();
    private final Map<String, Long> realmIdByNameLower = new ConcurrentHashMap<>();
    private final Map<UUID, Resident> residentsByUuid = new ConcurrentHashMap<>();
    private final Map<Long, Set<UUID>> residentsByRealm = new ConcurrentHashMap<>();
    private final Map<ClaimKey, Long> claimToRealm = new ConcurrentHashMap<>();
    private final Map<Long, Set<ClaimKey>> claimsByRealm = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Boolean>> flagsByRealm = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, Relation>> outRelations = new ConcurrentHashMap<>(); // a -> b -> relation
    private final Map<Long, Map<Long, Relation>> inRelations  = new ConcurrentHashMap<>(); // b -> a -> relation
    private final Map<Long, Map<String, Long>> cooldowns = new ConcurrentHashMap<>(); // a -> "b:kind" -> until
    private final Map<ClaimKey, Map<String, Integer>> ledger = new ConcurrentHashMap<>();
    private final Map<UUID, DisplayPrefs> displayPrefs = new ConcurrentHashMap<>();

    private Connection writerConn;
    private final LinkedBlockingQueue<WriteOp> queue = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private Thread writerThread;

    public SqliteRealmsStore(Plugin plugin, File dbFile) {
        this.plugin = plugin;
        this.dbFile = dbFile;
    }

    public void open() throws SQLException {
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs()) plugin.getLogger().warning("Could not create data folder " + parent);
        }
        // Hint sqlite-jdbc where to extract its native lib so two plugins don't
        // collide on the same temp file.
        File nativeDir = new File(plugin.getDataFolder(), "native");
        if (!nativeDir.exists()) nativeDir.mkdirs();
        System.setProperty("org.sqlite.lib.path", nativeDir.getAbsolutePath());

        // Direct Driver instantiation — DriverManager doesn't see classes
        // loaded by a plugin classloader.
        Driver driver;
        try {
            driver = (Driver) Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SQLException("sqlite-jdbc not on classpath?", e);
        }

        Properties props = new Properties();
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        this.writerConn = driver.connect(url, props);
        this.writerConn.setAutoCommit(true);
        try (Statement st = writerConn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        bootstrapSchema(writerConn);
        loadAllIntoCaches(writerConn);

        this.running = true;
        this.writerThread = new Thread(this::writerLoop, "Realms-SqliteWriter");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    public void close() {
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
            try { writerThread.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        // Drain anything still queued.
        flushQueueOnce();
        try { if (writerConn != null) writerConn.close(); }
        catch (SQLException e) { plugin.getLogger().log(Level.WARNING, "Error closing realms DB", e); }
    }

    // ----------------------------------------------------------------------
    // Schema / bootstrap
    // ----------------------------------------------------------------------

    private void bootstrapSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS realms (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    founder_uuid TEXT NOT NULL,
                    peaceful INTEGER NOT NULL DEFAULT 0,
                    founded_millis INTEGER NOT NULL,
                    cached_power INTEGER NOT NULL DEFAULT 0,
                    home_world TEXT,
                    home_x REAL, home_y REAL, home_z REAL,
                    home_yaw REAL, home_pitch REAL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS residents (
                    uuid TEXT PRIMARY KEY,
                    realm_id INTEGER NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
                    role TEXT NOT NULL,
                    joined_millis INTEGER NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_residents_realm ON residents(realm_id)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS claims (
                    world TEXT NOT NULL,
                    chunk_x INTEGER NOT NULL,
                    chunk_z INTEGER NOT NULL,
                    realm_id INTEGER NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
                    claimed_millis INTEGER NOT NULL,
                    PRIMARY KEY (world, chunk_x, chunk_z)
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_claims_realm ON claims(realm_id)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS power_ledger (
                    world TEXT NOT NULL,
                    chunk_x INTEGER NOT NULL,
                    chunk_z INTEGER NOT NULL,
                    material TEXT NOT NULL,
                    count INTEGER NOT NULL,
                    PRIMARY KEY (world, chunk_x, chunk_z, material)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS flags (
                    realm_id INTEGER NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
                    name TEXT NOT NULL,
                    value INTEGER NOT NULL,
                    PRIMARY KEY (realm_id, name)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS relations (
                    realm_a INTEGER NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
                    realm_b INTEGER NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
                    kind TEXT NOT NULL,
                    established_millis INTEGER NOT NULL,
                    PRIMARY KEY (realm_a, realm_b)
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_relations_b ON relations(realm_b)");
            st.execute("""
                CREATE TABLE IF NOT EXISTS relation_cooldowns (
                    realm_a INTEGER NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
                    realm_b INTEGER NOT NULL REFERENCES realms(id) ON DELETE CASCADE,
                    kind TEXT NOT NULL,
                    until_millis INTEGER NOT NULL,
                    PRIMARY KEY (realm_a, realm_b, kind)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS display_prefs (
                    uuid TEXT PRIMARY KEY,
                    title_on INTEGER NOT NULL DEFAULT 1,
                    bar_mode TEXT NOT NULL DEFAULT 'ACTION',
                    sound_on INTEGER NOT NULL DEFAULT 0
                )""");
        }
    }

    private void loadAllIntoCaches(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, name, founder_uuid, peaceful, founded_millis, cached_power, " +
                             "home_world, home_x, home_y, home_z, home_yaw, home_pitch FROM realms")) {
            while (rs.next()) {
                Realm r = new Realm(
                        rs.getLong(1), rs.getString(2), UUID.fromString(rs.getString(3)),
                        rs.getInt(4) != 0, rs.getLong(5), rs.getLong(6),
                        rs.getString(7),
                        nullableDouble(rs, 8), nullableDouble(rs, 9), nullableDouble(rs, 10),
                        nullableFloat(rs, 11), nullableFloat(rs, 12)
                );
                realmsById.put(r.id(), r);
                realmIdByNameLower.put(r.name().toLowerCase(Locale.ROOT), r.id());
                claimsByRealm.computeIfAbsent(r.id(), __ -> ConcurrentHashMap.newKeySet());
                residentsByRealm.computeIfAbsent(r.id(), __ -> ConcurrentHashMap.newKeySet());
                flagsByRealm.computeIfAbsent(r.id(), __ -> new ConcurrentHashMap<>());
                outRelations.computeIfAbsent(r.id(), __ -> new ConcurrentHashMap<>());
                inRelations.computeIfAbsent(r.id(), __ -> new ConcurrentHashMap<>());
                cooldowns.computeIfAbsent(r.id(), __ -> new ConcurrentHashMap<>());
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, realm_id, role, joined_millis FROM residents")) {
            while (rs.next()) {
                UUID id = UUID.fromString(rs.getString(1));
                Resident res = new Resident(id, rs.getLong(2),
                        Role.valueOf(rs.getString(3)), rs.getLong(4));
                residentsByUuid.put(id, res);
                residentsByRealm.computeIfAbsent(res.realmId(), __ -> ConcurrentHashMap.newKeySet()).add(id);
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT world, chunk_x, chunk_z, realm_id FROM claims")) {
            while (rs.next()) {
                ClaimKey k = new ClaimKey(rs.getString(1), rs.getInt(2), rs.getInt(3));
                long realmId = rs.getLong(4);
                claimToRealm.put(k, realmId);
                claimsByRealm.computeIfAbsent(realmId, __ -> ConcurrentHashMap.newKeySet()).add(k);
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT world, chunk_x, chunk_z, material, count FROM power_ledger")) {
            while (rs.next()) {
                ClaimKey k = new ClaimKey(rs.getString(1), rs.getInt(2), rs.getInt(3));
                ledger.computeIfAbsent(k, __ -> new ConcurrentHashMap<>())
                        .put(rs.getString(4), rs.getInt(5));
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT realm_id, name, value FROM flags")) {
            while (rs.next()) {
                flagsByRealm.computeIfAbsent(rs.getLong(1), __ -> new ConcurrentHashMap<>())
                        .put(rs.getString(2), rs.getInt(3) != 0);
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT realm_a, realm_b, kind, established_millis FROM relations")) {
            while (rs.next()) {
                long a = rs.getLong(1), b = rs.getLong(2);
                Relation rel = new Relation(a, b, RelationKind.valueOf(rs.getString(3)), rs.getLong(4));
                outRelations.computeIfAbsent(a, __ -> new ConcurrentHashMap<>()).put(b, rel);
                inRelations.computeIfAbsent(b, __ -> new ConcurrentHashMap<>()).put(a, rel);
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT realm_a, realm_b, kind, until_millis FROM relation_cooldowns")) {
            while (rs.next()) {
                long a = rs.getLong(1), b = rs.getLong(2);
                String key = b + ":" + rs.getString(3);
                cooldowns.computeIfAbsent(a, __ -> new ConcurrentHashMap<>())
                        .put(key, rs.getLong(4));
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, title_on, bar_mode, sound_on FROM display_prefs")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString(1));
                DisplayPrefs.BarMode mode;
                try { mode = DisplayPrefs.BarMode.valueOf(rs.getString(3)); }
                catch (IllegalArgumentException e) { mode = DisplayPrefs.BarMode.ACTION; }
                displayPrefs.put(uuid, new DisplayPrefs(rs.getInt(2) != 0, mode, rs.getInt(4) != 0));
            }
        }
    }

    private static Double nullableDouble(ResultSet rs, int idx) throws SQLException {
        double v = rs.getDouble(idx);
        return rs.wasNull() ? null : v;
    }
    private static Float nullableFloat(ResultSet rs, int idx) throws SQLException {
        float v = rs.getFloat(idx);
        return rs.wasNull() ? null : v;
    }

    // ----------------------------------------------------------------------
    // Writer thread
    // ----------------------------------------------------------------------

    private void writerLoop() {
        List<WriteOp> batch = new ArrayList<>(BATCH_MAX);
        while (running || !queue.isEmpty()) {
            try {
                WriteOp first = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, BATCH_MAX - 1);
                applyBatch(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Realms DB write failed", e);
                batch.clear();
            }
        }
    }

    private void flushQueueOnce() {
        List<WriteOp> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (batch.isEmpty()) return;
        try { applyBatch(batch); }
        catch (SQLException e) { plugin.getLogger().log(Level.SEVERE, "Realms DB final flush failed", e); }
    }

    private void applyBatch(List<WriteOp> ops) throws SQLException {
        writerConn.setAutoCommit(false);
        try {
            for (WriteOp op : ops) applyOp(op);
            writerConn.commit();
        } catch (SQLException e) {
            try { writerConn.rollback(); } catch (SQLException ignored) {}
            throw e;
        } finally {
            writerConn.setAutoCommit(true);
        }
    }

    private void applyOp(WriteOp op) throws SQLException {
        if (op instanceof WriteOp.UpsertRealm u) {
            Realm r = u.realm();
            try (PreparedStatement ps = writerConn.prepareStatement("""
                    INSERT INTO realms(id, name, founder_uuid, peaceful, founded_millis, cached_power,
                                       home_world, home_x, home_y, home_z, home_yaw, home_pitch)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET
                        name=excluded.name, founder_uuid=excluded.founder_uuid,
                        peaceful=excluded.peaceful, cached_power=excluded.cached_power,
                        home_world=excluded.home_world, home_x=excluded.home_x,
                        home_y=excluded.home_y, home_z=excluded.home_z,
                        home_yaw=excluded.home_yaw, home_pitch=excluded.home_pitch""")) {
                ps.setLong(1, r.id());
                ps.setString(2, r.name());
                ps.setString(3, r.founder().toString());
                ps.setInt(4, r.peaceful() ? 1 : 0);
                ps.setLong(5, r.foundedMillis());
                ps.setLong(6, r.cachedPower());
                ps.setString(7, r.homeWorld());
                setNullable(ps, 8, r.homeX()); setNullable(ps, 9, r.homeY()); setNullable(ps, 10, r.homeZ());
                setNullableF(ps, 11, r.homeYaw()); setNullableF(ps, 12, r.homePitch());
                ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.DeleteRealm d) {
            try (PreparedStatement ps = writerConn.prepareStatement("DELETE FROM realms WHERE id=?")) {
                ps.setLong(1, d.id()); ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.UpsertResident u) {
            Resident r = u.r();
            try (PreparedStatement ps = writerConn.prepareStatement("""
                    INSERT INTO residents(uuid, realm_id, role, joined_millis) VALUES (?,?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET realm_id=excluded.realm_id,
                        role=excluded.role, joined_millis=excluded.joined_millis""")) {
                ps.setString(1, r.uuid().toString());
                ps.setLong(2, r.realmId());
                ps.setString(3, r.role().name());
                ps.setLong(4, r.joinedMillis());
                ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.DeleteResident d) {
            try (PreparedStatement ps = writerConn.prepareStatement("DELETE FROM residents WHERE uuid=?")) {
                ps.setString(1, d.uuid().toString()); ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.AddClaims a) {
            try (PreparedStatement ps = writerConn.prepareStatement(
                    "INSERT OR REPLACE INTO claims(world, chunk_x, chunk_z, realm_id, claimed_millis) VALUES (?,?,?,?,?)")) {
                for (ClaimKey k : a.keys()) {
                    ps.setString(1, k.world()); ps.setInt(2, k.chunkX()); ps.setInt(3, k.chunkZ());
                    ps.setLong(4, a.realmId()); ps.setLong(5, a.millis());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } else if (op instanceof WriteOp.RemoveClaim r) {
            try (PreparedStatement ps = writerConn.prepareStatement(
                    "DELETE FROM claims WHERE world=? AND chunk_x=? AND chunk_z=?")) {
                ps.setString(1, r.key().world()); ps.setInt(2, r.key().chunkX()); ps.setInt(3, r.key().chunkZ());
                ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.TransferClaim t) {
            try (PreparedStatement ps = writerConn.prepareStatement(
                    "UPDATE claims SET realm_id=?, claimed_millis=? WHERE world=? AND chunk_x=? AND chunk_z=?")) {
                ps.setLong(1, t.toId()); ps.setLong(2, t.millis());
                ps.setString(3, t.key().world()); ps.setInt(4, t.key().chunkX()); ps.setInt(5, t.key().chunkZ());
                ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.DeltaPower d) {
            try (PreparedStatement ps = writerConn.prepareStatement("""
                    INSERT INTO power_ledger(world, chunk_x, chunk_z, material, count) VALUES (?,?,?,?,?)
                    ON CONFLICT(world, chunk_x, chunk_z, material) DO UPDATE SET
                        count=MAX(0, count + excluded.count)""")) {
                ps.setString(1, d.key().world()); ps.setInt(2, d.key().chunkX()); ps.setInt(3, d.key().chunkZ());
                ps.setString(4, d.material()); ps.setInt(5, d.delta());
                ps.executeUpdate();
            }
            // Sweep zero-count rows to keep the table tidy.
            try (PreparedStatement ps = writerConn.prepareStatement(
                    "DELETE FROM power_ledger WHERE world=? AND chunk_x=? AND chunk_z=? AND count<=0")) {
                ps.setString(1, d.key().world()); ps.setInt(2, d.key().chunkX()); ps.setInt(3, d.key().chunkZ());
                ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.ClearLedger c) {
            try (PreparedStatement ps = writerConn.prepareStatement(
                    "DELETE FROM power_ledger WHERE world=? AND chunk_x=? AND chunk_z=?")) {
                ps.setString(1, c.key().world()); ps.setInt(2, c.key().chunkX()); ps.setInt(3, c.key().chunkZ());
                ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.SetFlag s) {
            try (PreparedStatement ps = writerConn.prepareStatement("""
                    INSERT INTO flags(realm_id, name, value) VALUES (?,?,?)
                    ON CONFLICT(realm_id, name) DO UPDATE SET value=excluded.value""")) {
                ps.setLong(1, s.realmId()); ps.setString(2, s.name()); ps.setInt(3, s.value() ? 1 : 0);
                ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.PutRelation p) {
            try (PreparedStatement ps = writerConn.prepareStatement("""
                    INSERT INTO relations(realm_a, realm_b, kind, established_millis) VALUES (?,?,?,?)
                    ON CONFLICT(realm_a, realm_b) DO UPDATE SET
                        kind=excluded.kind, established_millis=excluded.established_millis""")) {
                ps.setLong(1, p.r().realmA()); ps.setLong(2, p.r().realmB());
                ps.setString(3, p.r().kind().name()); ps.setLong(4, p.r().establishedMillis());
                ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.RemoveRelation r) {
            try (PreparedStatement ps = writerConn.prepareStatement(
                    "DELETE FROM relations WHERE realm_a=? AND realm_b=?")) {
                ps.setLong(1, r.a()); ps.setLong(2, r.b()); ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.PutCooldown c) {
            try (PreparedStatement ps = writerConn.prepareStatement("""
                    INSERT INTO relation_cooldowns(realm_a, realm_b, kind, until_millis) VALUES (?,?,?,?)
                    ON CONFLICT(realm_a, realm_b, kind) DO UPDATE SET until_millis=excluded.until_millis""")) {
                ps.setLong(1, c.a()); ps.setLong(2, c.b()); ps.setString(3, c.kind()); ps.setLong(4, c.until());
                ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.PurgeCooldowns p) {
            try (PreparedStatement ps = writerConn.prepareStatement(
                    "DELETE FROM relation_cooldowns WHERE until_millis <= ?")) {
                ps.setLong(1, p.cutoff()); ps.executeUpdate();
            }
        } else if (op instanceof WriteOp.PutDisplayPrefs p) {
            try (PreparedStatement ps = writerConn.prepareStatement("""
                    INSERT INTO display_prefs(uuid, title_on, bar_mode, sound_on) VALUES (?,?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET title_on=excluded.title_on,
                        bar_mode=excluded.bar_mode, sound_on=excluded.sound_on""")) {
                ps.setString(1, p.uuid().toString());
                ps.setInt(2, p.prefs().titleOn() ? 1 : 0);
                ps.setString(3, p.prefs().barMode().name());
                ps.setInt(4, p.prefs().soundOn() ? 1 : 0);
                ps.executeUpdate();
            }
        }
    }

    private static void setNullable(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.REAL); else ps.setDouble(idx, v);
    }
    private static void setNullableF(PreparedStatement ps, int idx, Float v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.REAL); else ps.setFloat(idx, v);
    }

    // ----------------------------------------------------------------------
    // RealmsStore impl — reads off hot maps, writes enqueue
    // ----------------------------------------------------------------------

    @Override
    public synchronized Realm createRealm(String name, UUID founder, boolean peaceful, long foundedMillis) {
        // Reserve an id by inserting synchronously so we can return the row.
        try {
            try (PreparedStatement ps = writerConn.prepareStatement(
                    "INSERT INTO realms(name, founder_uuid, peaceful, founded_millis, cached_power) VALUES (?,?,?,?,0)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, founder.toString());
                ps.setInt(3, peaceful ? 1 : 0);
                ps.setLong(4, foundedMillis);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("no generated id for realm");
                    long id = keys.getLong(1);
                    Realm r = new Realm(id, name, founder, peaceful, foundedMillis, 0L,
                            null, null, null, null, null, null);
                    realmsById.put(id, r);
                    realmIdByNameLower.put(name.toLowerCase(Locale.ROOT), id);
                    claimsByRealm.put(id, ConcurrentHashMap.newKeySet());
                    residentsByRealm.put(id, ConcurrentHashMap.newKeySet());
                    flagsByRealm.put(id, new ConcurrentHashMap<>());
                    outRelations.put(id, new ConcurrentHashMap<>());
                    inRelations.put(id, new ConcurrentHashMap<>());
                    cooldowns.put(id, new ConcurrentHashMap<>());
                    return r;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "createRealm failed", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void updateRealm(Realm realm) {
        realmsById.put(realm.id(), realm);
        // realm name change → keep name index consistent
        realmIdByNameLower.entrySet().removeIf(e -> e.getValue().equals(realm.id()));
        realmIdByNameLower.put(realm.name().toLowerCase(Locale.ROOT), realm.id());
        queue.add(new WriteOp.UpsertRealm(realm));
    }

    @Override
    public void deleteRealm(long realmId) {
        Realm r = realmsById.remove(realmId);
        if (r != null) realmIdByNameLower.remove(r.name().toLowerCase(Locale.ROOT));
        // Cascade in caches: residents
        Set<UUID> members = residentsByRealm.remove(realmId);
        if (members != null) for (UUID id : members) residentsByUuid.remove(id);
        // Claims + ledger
        Set<ClaimKey> claims = claimsByRealm.remove(realmId);
        if (claims != null) for (ClaimKey k : claims) {
            claimToRealm.remove(k);
            ledger.remove(k);
        }
        flagsByRealm.remove(realmId);
        // Relations (both directions)
        Map<Long, Relation> out = outRelations.remove(realmId);
        if (out != null) for (Long other : out.keySet()) {
            Map<Long, Relation> in = inRelations.get(other);
            if (in != null) in.remove(realmId);
        }
        Map<Long, Relation> in = inRelations.remove(realmId);
        if (in != null) for (Long other : in.keySet()) {
            Map<Long, Relation> oo = outRelations.get(other);
            if (oo != null) oo.remove(realmId);
        }
        cooldowns.remove(realmId);
        queue.add(new WriteOp.DeleteRealm(realmId));
    }

    @Override
    public Realm getRealm(long realmId) { return realmsById.get(realmId); }

    @Override
    public Realm getRealmByName(String name) {
        if (name == null) return null;
        Long id = realmIdByNameLower.get(name.toLowerCase(Locale.ROOT));
        return id == null ? null : realmsById.get(id);
    }

    @Override
    public Collection<Realm> allRealms() { return Collections.unmodifiableCollection(realmsById.values()); }

    @Override
    public void upsertResident(Resident resident) {
        Resident prev = residentsByUuid.put(resident.uuid(), resident);
        if (prev != null && prev.realmId() != resident.realmId()) {
            Set<UUID> prevSet = residentsByRealm.get(prev.realmId());
            if (prevSet != null) prevSet.remove(resident.uuid());
        }
        residentsByRealm.computeIfAbsent(resident.realmId(), __ -> ConcurrentHashMap.newKeySet())
                .add(resident.uuid());
        queue.add(new WriteOp.UpsertResident(resident));
    }

    @Override
    public void removeResident(UUID uuid) {
        Resident r = residentsByUuid.remove(uuid);
        if (r != null) {
            Set<UUID> set = residentsByRealm.get(r.realmId());
            if (set != null) set.remove(uuid);
        }
        queue.add(new WriteOp.DeleteResident(uuid));
    }

    @Override
    public Resident getResident(UUID uuid) { return residentsByUuid.get(uuid); }

    @Override
    public List<Resident> residentsOf(long realmId) {
        Set<UUID> ids = residentsByRealm.get(realmId);
        if (ids == null) return Collections.emptyList();
        List<Resident> out = new ArrayList<>(ids.size());
        for (UUID u : ids) {
            Resident r = residentsByUuid.get(u);
            if (r != null) out.add(r);
        }
        return out;
    }

    @Override
    public int residentCount(long realmId) {
        Set<UUID> ids = residentsByRealm.get(realmId);
        return ids == null ? 0 : ids.size();
    }

    @Override
    public Long claimOwner(ClaimKey key) { return claimToRealm.get(key); }

    @Override
    public void addClaims(long realmId, Collection<ClaimKey> keys, long claimedMillis) {
        Set<ClaimKey> set = claimsByRealm.computeIfAbsent(realmId, __ -> ConcurrentHashMap.newKeySet());
        for (ClaimKey k : keys) {
            claimToRealm.put(k, realmId);
            set.add(k);
        }
        queue.add(new WriteOp.AddClaims(realmId, new ArrayList<>(keys), claimedMillis));
    }

    @Override
    public void removeClaim(ClaimKey key) {
        Long realmId = claimToRealm.remove(key);
        if (realmId != null) {
            Set<ClaimKey> set = claimsByRealm.get(realmId);
            if (set != null) set.remove(key);
        }
        ledger.remove(key);
        queue.add(new WriteOp.RemoveClaim(key));
        queue.add(new WriteOp.ClearLedger(key));
    }

    @Override
    public Collection<ClaimKey> claimsOf(long realmId) {
        Set<ClaimKey> set = claimsByRealm.get(realmId);
        return set == null ? Collections.emptySet() : Collections.unmodifiableSet(new HashSet<>(set));
    }

    @Override
    public int claimCount(long realmId) {
        Set<ClaimKey> set = claimsByRealm.get(realmId);
        return set == null ? 0 : set.size();
    }

    @Override
    public void transferClaim(ClaimKey key, long fromRealmId, long toRealmId, long millis) {
        Set<ClaimKey> from = claimsByRealm.get(fromRealmId);
        if (from != null) from.remove(key);
        claimsByRealm.computeIfAbsent(toRealmId, __ -> ConcurrentHashMap.newKeySet()).add(key);
        claimToRealm.put(key, toRealmId);
        queue.add(new WriteOp.TransferClaim(key, fromRealmId, toRealmId, millis));
    }

    @Override
    public void deltaPower(ClaimKey key, Material material, int delta) {
        if (delta == 0) return;
        Map<String, Integer> chunkLedger = ledger.computeIfAbsent(key, __ -> new ConcurrentHashMap<>());
        chunkLedger.merge(material.name(), delta, (cur, d) -> Math.max(0, cur + d));
        if (chunkLedger.getOrDefault(material.name(), 0) <= 0) chunkLedger.remove(material.name());
        queue.add(new WriteOp.DeltaPower(key, material.name(), delta));
    }

    @Override
    public long ledgerPower(ClaimKey key, Map<Material, Long> valueTable) {
        Map<String, Integer> chunk = ledger.get(key);
        if (chunk == null || chunk.isEmpty()) return 0L;
        long sum = 0L;
        for (Map.Entry<String, Integer> e : chunk.entrySet()) {
            Material m;
            try { m = Material.valueOf(e.getKey()); }
            catch (IllegalArgumentException ex) { continue; }
            Long v = valueTable.get(m);
            if (v != null) sum += v * e.getValue();
        }
        return sum;
    }

    @Override
    public long ledgerPower(long realmId, Map<Material, Long> valueTable) {
        Set<ClaimKey> claims = claimsByRealm.get(realmId);
        if (claims == null) return 0L;
        long sum = 0L;
        for (ClaimKey k : claims) sum += ledgerPower(k, valueTable);
        return sum;
    }

    @Override
    public void clearLedger(ClaimKey key) {
        ledger.remove(key);
        queue.add(new WriteOp.ClearLedger(key));
    }

    @Override
    public Map<Material, Integer> ledgerCounts(ClaimKey key) {
        Map<String, Integer> chunk = ledger.get(key);
        if (chunk == null || chunk.isEmpty()) return Collections.emptyMap();
        Map<Material, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : chunk.entrySet()) {
            try { out.put(Material.valueOf(e.getKey()), e.getValue()); }
            catch (IllegalArgumentException ignored) { /* obsolete material */ }
        }
        return out;
    }

    @Override
    public void setFlag(long realmId, String flag, boolean value) {
        flagsByRealm.computeIfAbsent(realmId, __ -> new ConcurrentHashMap<>()).put(flag, value);
        queue.add(new WriteOp.SetFlag(realmId, flag, value));
    }

    @Override
    public Map<String, Boolean> flagsOf(long realmId) {
        Map<String, Boolean> m = flagsByRealm.get(realmId);
        return m == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(m));
    }

    @Override
    public void putRelation(Relation relation) {
        outRelations.computeIfAbsent(relation.realmA(), __ -> new ConcurrentHashMap<>())
                .put(relation.realmB(), relation);
        inRelations.computeIfAbsent(relation.realmB(), __ -> new ConcurrentHashMap<>())
                .put(relation.realmA(), relation);
        queue.add(new WriteOp.PutRelation(relation));
    }

    @Override
    public void removeRelation(long realmA, long realmB) {
        Map<Long, Relation> out = outRelations.get(realmA);
        if (out != null) out.remove(realmB);
        Map<Long, Relation> in = inRelations.get(realmB);
        if (in != null) in.remove(realmA);
        queue.add(new WriteOp.RemoveRelation(realmA, realmB));
    }

    @Override
    public Collection<Relation> relationsFrom(long realmA) {
        Map<Long, Relation> m = outRelations.get(realmA);
        return m == null ? Collections.emptySet() : Collections.unmodifiableCollection(new ArrayList<>(m.values()));
    }

    @Override
    public Collection<Relation> relationsTo(long realmB) {
        Map<Long, Relation> m = inRelations.get(realmB);
        return m == null ? Collections.emptySet() : Collections.unmodifiableCollection(new ArrayList<>(m.values()));
    }

    @Override
    public void putCooldown(long realmA, long realmB, RelationKind kind, long untilMillis) {
        cooldowns.computeIfAbsent(realmA, __ -> new ConcurrentHashMap<>())
                .put(realmB + ":" + kind.name(), untilMillis);
        queue.add(new WriteOp.PutCooldown(realmA, realmB, kind.name(), untilMillis));
    }

    @Override
    public long getCooldown(long realmA, long realmB, RelationKind kind) {
        Map<String, Long> m = cooldowns.get(realmA);
        if (m == null) return 0L;
        Long until = m.get(realmB + ":" + kind.name());
        return until == null ? 0L : until;
    }

    @Override
    public void purgeExpiredCooldowns(long nowMillis) {
        for (Map<String, Long> m : cooldowns.values()) {
            m.entrySet().removeIf(e -> e.getValue() <= nowMillis);
        }
        queue.add(new WriteOp.PurgeCooldowns(nowMillis));
    }

    @Override
    public DisplayPrefs getDisplayPrefs(UUID uuid) {
        DisplayPrefs p = displayPrefs.get(uuid);
        return p == null ? DisplayPrefs.defaults() : p;
    }

    @Override
    public void putDisplayPrefs(UUID uuid, DisplayPrefs prefs) {
        displayPrefs.put(uuid, prefs);
        queue.add(new WriteOp.PutDisplayPrefs(uuid, prefs));
    }
}
