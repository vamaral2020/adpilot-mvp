package br.com.adpilot.integrations.nuvemshop;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NuvemshopRepository {
    private static final Logger LOG = LoggerFactory.getLogger(NuvemshopRepository.class);
    public static final UUID WORKSPACE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private final JdbcTemplate jdbc;

    public record Installation(String storeId, String scope, byte[] tokenEncrypted, Instant connectedAt) { }

    public NuvemshopRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createState(byte[] digest) {
        jdbc.update("DELETE FROM nuvemshop_oauth_state WHERE expires_at < NOW()");
        jdbc.update("INSERT INTO nuvemshop_oauth_state (state_hash, workspace_id, expires_at) " +
                "VALUES (?, ?, NOW() + INTERVAL '5 minutes')", digest, WORKSPACE);
    }

    // Independent transaction: the state is consumed even if token exchange fails.
    // PostgreSQL DELETE RETURNING is atomic and prevents callback replay.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consumeState(byte[] digest) {
        List<UUID> ids = jdbc.query(
                "DELETE FROM nuvemshop_oauth_state WHERE state_hash = ? " +
                "AND expires_at > NOW() RETURNING workspace_id",
                (rs, rowNum) -> rs.getObject("workspace_id", UUID.class), digest);
        if (ids.size() != 1 || !WORKSPACE.equals(ids.getFirst())) {
            // A matching entry, if still present, can only be expired here.
            // Count-only diagnostics do not expose tokens, code or the state itself.
            Integer matchingExpired = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM nuvemshop_oauth_state WHERE state_hash = ? AND expires_at <= NOW()",
                    Integer.class, digest);
            LOG.warn("NUVEMSHOP_OAUTH_STATE_REJECTED state_ref={} reason={}",
                    NuvemshopAuthService.stateRef(digest),
                    matchingExpired != null && matchingExpired > 0 ? "expired" : "not_found_or_consumed");
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "NUVEMSHOP_INVALID_STATE",
                    "Autorizacao expirada, invalida ou ja utilizada. Inicie a conexao novamente.");
        }
    }

    @Transactional
    public void saveInstallation(String storeId, String scope, byte[] encryptedToken) {
        jdbc.update("""
                INSERT INTO nuvemshop_installation (workspace_id, store_id, scope, token_encrypted)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (workspace_id) DO UPDATE SET
                  store_id = EXCLUDED.store_id,
                  scope = EXCLUDED.scope,
                  token_encrypted = EXCLUDED.token_encrypted,
                  connected_at = NOW(),
                  updated_at = NOW()
                """, WORKSPACE, storeId, scope, encryptedToken);
        jdbc.update("""
                INSERT INTO integration_connection
                  (id, workspace_id, provider, status, external_account_id)
                VALUES (?, ?, 'NUVEMSHOP', 'CONNECTED', ?)
                ON CONFLICT (workspace_id, provider) DO UPDATE SET
                  status = 'CONNECTED', external_account_id = EXCLUDED.external_account_id,
                  updated_at = NOW()
                """, UUID.randomUUID(), WORKSPACE, storeId);
    }

    public Optional<Installation> installation() {
        List<Installation> rows = jdbc.query("""
                SELECT store_id, scope, token_encrypted, connected_at
                FROM nuvemshop_installation WHERE workspace_id = ?
                """, (rs, rowNum) -> new Installation(rs.getString("store_id"),
                rs.getString("scope"), rs.getBytes("token_encrypted"),
                rs.getTimestamp("connected_at").toInstant()), WORKSPACE);
        return rows.stream().findFirst();
    }
}
