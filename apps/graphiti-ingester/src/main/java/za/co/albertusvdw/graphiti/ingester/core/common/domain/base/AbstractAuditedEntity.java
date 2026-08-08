package za.co.albertusvdw.graphiti.ingester.core.common.domain.base;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Identity and audit timestamps for every persisted entity.
 *
 * <p>These fields record when a <em>row</em> changed and must never be read as business
 * facts. When an episode was ingested is {@code postedAt}/{@code verifiedAt} on the entity
 * itself, not {@code createdDateTime} — the two diverge the moment a row is retried.
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AbstractAuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreatedDate
    @Column(name = "created_date_time", nullable = false, updatable = false)
    private Instant createdDateTime;

    @LastModifiedDate
    @Column(name = "last_modified_date_time", nullable = false)
    private Instant lastModifiedDateTime;
}
