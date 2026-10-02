package com.agentic.shortener.repository;

import com.agentic.shortener.domain.ShortLink;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShortLinkRepository extends JpaRepository<ShortLink, Long> {

    Optional<ShortLink> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Atomic in-database increment (NFR-3): no read-modify-write, so concurrent batches can
     * never lose updates. Bypasses the entity's @Version on purpose: a click must not make a
     * concurrent deactivation fail with an optimistic-lock conflict.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE short_link
               SET click_count = click_count + :clicks,
                   last_accessed_at = CASE WHEN last_accessed_at IS NULL OR last_accessed_at < :at
                                           THEN :at ELSE last_accessed_at END
             WHERE id = :id""")
    int incrementClicks(@Param("id") long id, @Param("clicks") long clicks, @Param("at") Instant at);
}
