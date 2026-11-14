package com.example.perfume_budget.theme;

import com.example.perfume_budget.model.StoreSettings;
import com.example.perfume_budget.repository.StoreSettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Persistence for the singleton {@code store_settings} row of the current tenant. Kept separate from
 * {@link ThemeServiceImpl} so the cached read does not open a transaction on a cache hit and so uploads never
 * run inside a database transaction.
 *
 * <p>Document stamping (PO decision): on every read and save the document's {@code revision} is the entity
 * {@code version - 1} and {@code updatedAt} is the entity's {@code updated_at}; the ETag is {@code W/"<version>"}.
 */
@Component
@RequiredArgsConstructor
public class ThemeStore {

    private static final String DEFAULT_RESOURCE = "theme/default-theme.json";

    private final StoreSettingsRepository repository;
    private final ObjectMapper mapper;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public ThemeSnapshot read() {
        return snapshot(loadOrHeal());
    }

    /**
     * Replaces the stored document.
     *
     * @param expectedVersion entity version the caller last saw, or null for last-write-wins
     * @throws ThemeConflictException when the version is stale or a concurrent write won
     */
    @Transactional
    public ThemeSnapshot save(JsonNode document, Long expectedVersion, String updatedBy) {
        StoreSettings s = loadOrHeal();
        if (expectedVersion != null && !expectedVersion.equals(s.getVersion())) {
            throw new ThemeConflictException("Theme was modified by someone else; reload and retry");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        long next = s.getVersion() + 1;
        ObjectNode doc = document.deepCopy();
        stamp(doc, next, now);
        s.setTheme(doc);
        s.setSchemaVersion(doc.path("schemaVersion").asInt(1)); // keeps schema_version == theme.schemaVersion
        s.setUpdatedAt(now);
        s.setUpdatedBy(updatedBy);
        try {
            repository.saveAndFlush(s);
        } catch (OptimisticLockingFailureException e) {
            throw new ThemeConflictException("Theme was modified by someone else; reload and retry");
        }
        if (s.getVersion() != next) {
            throw new IllegalStateException("Unexpected version after save");
        }
        return new ThemeSnapshot(doc.toString(), s.getVersion());
    }

    private StoreSettings loadOrHeal() {
        return repository.findTopByOrderByIdAsc().orElseGet(this::heal);
    }

    /** Self-heal: the singleton row is missing (migration is the normal source), so create the contract default. */
    private StoreSettings heal() {
        // Serialise concurrent heals within this tenant schema so we never create two singleton rows.
        em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(current_schema() || ':store_settings'))").getSingleResult();
        return repository.findTopByOrderByIdAsc().orElseGet(() -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
            em.createNativeQuery("INSERT INTO store_settings (schema_version, theme, version, created_at, updated_at) "
                            + "VALUES (1, CAST(:theme AS jsonb), 1, :now, :now)")
                    .setParameter("theme", defaultDocument().toString())
                    .setParameter("now", now)
                    .executeUpdate();
            return repository.findTopByOrderByIdAsc().orElseThrow();
        });
    }

    /** The contract default (same document as the V8 migration; a test keeps them identical). */
    public JsonNode defaultDocument() {
        try (InputStream in = ThemeStore.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + DEFAULT_RESOURCE);
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + DEFAULT_RESOURCE, e);
        }
    }

    private ThemeSnapshot snapshot(StoreSettings s) {
        ObjectNode doc = ((ObjectNode) s.getTheme()).deepCopy();
        LocalDateTime at = s.getUpdatedAt() != null ? s.getUpdatedAt() : s.getCreatedAt();
        stamp(doc, s.getVersion(), at);
        return new ThemeSnapshot(doc.toString(), s.getVersion());
    }

    static void stamp(ObjectNode doc, long version, LocalDateTime updatedAt) {
        // int (IntNode), not long: Hibernate dirty-checks the JSON after a round trip, where small numbers come back
        // as IntNode; a LongNode would look "modified" and cause a second UPDATE (double version bump).
        doc.put("revision", (int) Math.min(Integer.MAX_VALUE, Math.max(0, version - 1)));
        if (updatedAt != null) {
            doc.put("updatedAt", updatedAt.toInstant(ZoneOffset.UTC).toString());
        }
    }
}
