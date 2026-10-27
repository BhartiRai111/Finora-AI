package com.finora.finance.reference;

import com.finora.commons.authz.Action;
import com.finora.commons.error.ApiException;
import com.finora.commons.error.ErrorCode;
import com.finora.finance.core.FinanceAudit;
import com.finora.finance.core.FinanceContext;
import com.finora.finance.core.Validate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Categories (one level of subcategories), merge with replacement, and tags. Nothing is ever hard-deleted. */
@Service
public class ReferenceService {

    public record CategoryRow(UUID id, UUID parentId, String name, String kind, OffsetDateTime archivedAt, int version) {
    }

    public record CategoryView(UUID id, UUID parentId, String name, String kind, boolean archived, int version) {
    }

    public record TagView(UUID id, String name, long uses) {
    }

    public record CategoryRequest(String name, String kind, String parentId, Integer version) {
    }

    private static final String COLS = "id, parent_id, name, kind, archived_at, version";
    private static final List<String> STARTER_EXPENSE = List.of("Groceries", "Rent", "Utilities", "Transport", "Dining", "Health",
            "Entertainment", "Shopping", "Education", "Travel", "Other");
    private static final List<String> STARTER_INCOME = List.of("Salary", "Business", "Interest", "Gifts", "Other");

    private final JdbcClient jdbc;
    private final FinanceAudit audit;

    public ReferenceService(JdbcClient jdbc, FinanceAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static OffsetDateTime now(FinanceContext ctx) {
        return OffsetDateTime.ofInstant(ctx.clock().instant(), ZoneOffset.UTC);
    }

    private static CategoryView view(CategoryRow r) {
        return new CategoryView(r.id(), r.parentId(), r.name(), r.kind(), r.archivedAt() != null, r.version());
    }

    // ---- categories ----

    public List<CategoryView> categories(FinanceContext ctx, boolean includeArchived) {
        ctx.require(Action.READ);
        return jdbc.sql("select " + COLS + " from categories where tenant_id = :t " + (includeArchived ? "" : "and archived_at is null ")
                + "order by kind, lower(name), id").param("t", ctx.tenantId()).query(CategoryRow.class).list().stream()
                .map(ReferenceService::view).toList();
    }

    public CategoryRow require(UUID tenant, UUID id) {
        return jdbc.sql("select " + COLS + " from categories where tenant_id = :t and id = :id").param("t", tenant).param("id", id)
                .query(CategoryRow.class).optional().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    /** A category referenced from a request body: missing and foreign ids look identical; the kind must match. */
    public CategoryRow requireUsable(UUID tenant, UUID id, String expectedKind, String field) {
        CategoryRow r = jdbc.sql("select " + COLS + " from categories where tenant_id = :t and id = :id").param("t", tenant).param("id", id)
                .query(CategoryRow.class).optional().orElseThrow(() -> Validate.invalid(field + " does not exist"));
        if (r.archivedAt() != null) {
            throw Validate.invalid(field + " is archived");
        }
        if (expectedKind != null && !expectedKind.equals(r.kind())) {
            throw Validate.invalid(field + " must be an " + expectedKind.toLowerCase(Locale.ROOT) + " category");
        }
        return r;
    }

    /** The category and its direct subcategories (budgets and filters on a parent include its children). */
    public List<UUID> withChildren(UUID tenant, UUID id) {
        List<UUID> ids = new ArrayList<>(List.of(id));
        ids.addAll(jdbc.sql("select id from categories where tenant_id = :t and parent_id = :id").param("t", tenant).param("id", id)
                .query(UUID.class).list());
        return ids;
    }

    @Transactional
    public CategoryView create(FinanceContext ctx, CategoryRequest req) {
        ctx.require(Action.CREATE);
        String name = Validate.text("name", req.name(), 1, 60, true);
        String kind = Validate.choice("kind", req.kind(), Kind.class).name();
        UUID parentId = Validate.uuid("parent_id", req.parentId(), false);
        if (parentId != null) {
            CategoryRow parent = requireUsable(ctx.tenantId(), parentId, kind, "parent_id");
            if (parent.parentId() != null) {
                throw Validate.invalid("subcategories can only be one level deep");
            }
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("insert into categories (id, tenant_id, parent_id, name, kind, created_at, updated_at) values (:id, :t, :p, :n, :k, :now, :now)")
                    .param("id", id).param("t", ctx.tenantId()).param("p", parentId).param("n", name).param("k", kind)
                    .param("now", now(ctx)).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A category with that name already exists here");
        }
        audit.record(ctx, "CATEGORY_CREATED", "CATEGORY", id, kind + " " + name);
        return view(require(ctx.tenantId(), id));
    }

    enum Kind { INCOME, EXPENSE }

    @Transactional
    public CategoryView rename(FinanceContext ctx, UUID id, String newName, Integer version) {
        ctx.require(Action.UPDATE);
        String name = Validate.text("name", newName, 1, 60, true);
        int v = Validate.version(version);
        CategoryRow before = require(ctx.tenantId(), id);
        int updated;
        try {
            updated = jdbc.sql("update categories set name = :n, version = version + 1, updated_at = :now where tenant_id = :t and id = :id and version = :v")
                    .param("n", name).param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).param("v", v).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A category with that name already exists here");
        }
        if (updated == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "This category was changed elsewhere; reload and try again");
        }
        audit.record(ctx, "CATEGORY_RENAMED", "CATEGORY", id, before.name() + " -> " + name);
        return view(require(ctx.tenantId(), id));
    }

    @Transactional
    public CategoryView setArchived(FinanceContext ctx, UUID id, boolean archived) {
        ctx.require(Action.ARCHIVE);
        CategoryRow row = require(ctx.tenantId(), id);
        if (archived && jdbc.sql("select count(*) from categories where tenant_id = :t and parent_id = :id and archived_at is null")
                .param("t", ctx.tenantId()).param("id", id).query(Long.class).single() > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Archive or merge its subcategories first");
        }
        if (!archived && row.parentId() != null && require(ctx.tenantId(), row.parentId()).archivedAt() != null) {
            throw new ApiException(ErrorCode.CONFLICT, "Restore the parent category first");
        }
        try {
            jdbc.sql("update categories set archived_at = :a, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                    .param("a", archived ? now(ctx) : null).param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "Another active category already uses this name");
        }
        audit.record(ctx, archived ? "CATEGORY_ARCHIVED" : "CATEGORY_RESTORED", "CATEGORY", id, null);
        return view(require(ctx.tenantId(), id));
    }

    /**
     * Moves everything from {@code id} into {@code intoId} atomically and archives the source (delete requires a
     * replacement, PF-CAT-1). Refuses ambiguous cases instead of choosing for the user.
     */
    @Transactional
    public CategoryView merge(FinanceContext ctx, UUID id, String intoRaw) {
        ctx.require(Action.UPDATE);
        UUID into = Validate.uuid("into_category_id", intoRaw, true);
        if (id.equals(into)) {
            throw Validate.invalid("choose a different category to merge into");
        }
        jdbc.sql("select id from categories where tenant_id = :t and id in (:a, :b) order by id for update")
                .param("t", ctx.tenantId()).param("a", id).param("b", into).query(UUID.class).list();
        CategoryRow source = require(ctx.tenantId(), id);
        CategoryRow target = requireUsable(ctx.tenantId(), into, source.kind(), "into_category_id");
        if (source.archivedAt() != null) {
            throw new ApiException(ErrorCode.CONFLICT, "That category is already archived");
        }
        if (jdbc.sql("select count(*) from categories where tenant_id = :t and parent_id = :id and archived_at is null")
                .param("t", ctx.tenantId()).param("id", id).query(Long.class).single() > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Merge or archive its subcategories first");
        }
        long clash = jdbc.sql("""
                select count(*) from budgets s join budgets t on t.tenant_id = s.tenant_id and t.currency = s.currency
                where s.tenant_id = :t and s.category_id = :src and t.category_id = :dst and s.archived_at is null and t.archived_at is null
                """).param("t", ctx.tenantId()).param("src", id).param("dst", into).query(Long.class).single();
        if (clash > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Both categories have a budget in the same currency; archive one budget first");
        }
        int tx = jdbc.sql("update transactions set category_id = :dst, updated_at = :now, version = version + 1 where tenant_id = :t and category_id = :src")
                .param("dst", into).param("src", id).param("now", now(ctx)).param("t", ctx.tenantId()).update();
        jdbc.sql("update recurring_rules set category_id = :dst where tenant_id = :t and category_id = :src").param("dst", into).param("src", id).param("t", ctx.tenantId()).update();
        jdbc.sql("update commitments set category_id = :dst where tenant_id = :t and category_id = :src").param("dst", into).param("src", id).param("t", ctx.tenantId()).update();
        jdbc.sql("update budgets set category_id = :dst, version = version + 1, updated_at = :now where tenant_id = :t and category_id = :src")
                .param("dst", into).param("src", id).param("now", now(ctx)).param("t", ctx.tenantId()).update();
        jdbc.sql("update categories set archived_at = :now, version = version + 1, updated_at = :now where tenant_id = :t and id = :id")
                .param("now", now(ctx)).param("t", ctx.tenantId()).param("id", id).update();
        audit.record(ctx, "CATEGORY_MERGED", "CATEGORY", id, "into " + target.id() + " (" + tx + " transactions moved)");
        return view(require(ctx.tenantId(), into));
    }

    /** An explicit user action: adds the suggested starter categories that do not exist yet. */
    @Transactional
    public List<CategoryView> addStarterCategories(FinanceContext ctx) {
        ctx.require(Action.CREATE);
        int created = 0;
        for (String name : STARTER_EXPENSE) {
            created += insertIfMissing(ctx, name, "EXPENSE");
        }
        for (String name : STARTER_INCOME) {
            created += insertIfMissing(ctx, name, "INCOME");
        }
        audit.record(ctx, "STARTER_CATEGORIES_ADDED", "CATEGORY", null, created + " created");
        return categories(ctx, false);
    }

    private int insertIfMissing(FinanceContext ctx, String name, String kind) {
        return jdbc.sql("""
                insert into categories (id, tenant_id, name, kind, created_at, updated_at)
                select :id, :t, :n, :k, :now, :now
                where not exists (select 1 from categories where tenant_id = :t and kind = :k and parent_id is null and lower(name) = lower(:n) and archived_at is null)
                """).param("id", UUID.randomUUID()).param("t", ctx.tenantId()).param("n", name).param("k", kind).param("now", now(ctx)).update();
    }

    // ---- tags ----

    public List<TagView> tags(FinanceContext ctx) {
        ctx.require(Action.READ);
        return jdbc.sql("""
                select t.id, t.name, count(tt.tag_id) as uses from tags t
                left join transaction_tags tt on tt.tenant_id = t.tenant_id and tt.tag_id = t.id
                where t.tenant_id = :t group by t.id, t.name order by t.name
                """).param("t", ctx.tenantId()).query(TagView.class).list();
    }

    @Transactional
    public TagView renameTag(FinanceContext ctx, UUID id, String newName) {
        ctx.require(Action.UPDATE);
        String name = Validate.tags(List.of(newName == null ? "" : newName)).get(0);
        int updated;
        try {
            updated = jdbc.sql("update tags set name = :n where tenant_id = :t and id = :id").param("n", name).param("t", ctx.tenantId()).param("id", id).update();
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.CONFLICT, "A tag with that name already exists");
        }
        if (updated == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, null);
        }
        audit.record(ctx, "TAG_RENAMED", "TAG", id, "-> " + name);
        return tags(ctx).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    /** Creates missing tags and returns the ids of all of them (names are already normalised). */
    public List<UUID> upsertTags(FinanceContext ctx, List<String> names) {
        List<UUID> ids = new ArrayList<>();
        for (String name : names) {
            jdbc.sql("insert into tags (id, tenant_id, name, created_at) values (:id, :t, :n, :now) on conflict (tenant_id, name) do nothing")
                    .param("id", UUID.randomUUID()).param("t", ctx.tenantId()).param("n", name).param("now", now(ctx)).update();
            ids.add(jdbc.sql("select id from tags where tenant_id = :t and name = :n").param("t", ctx.tenantId()).param("n", name)
                    .query(UUID.class).single());
        }
        return ids;
    }
}
