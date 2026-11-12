package com.freecontent.community.content.browse.service.transaction;

import com.freecontent.community.common.error.*;
import com.freecontent.community.content.browse.cursor.PublicContentCursor;
import com.freecontent.community.content.browse.dto.*;
import com.freecontent.community.content.browse.mapper.PublicContentMapper;
import com.freecontent.community.content.browse.mapper.PublicContentMapper.*;
import com.freecontent.community.content.browse.model.PublicContentListQuery;
import com.freecontent.community.content.browse.service.read.PublicContentAssembler;
import com.freecontent.community.user.service.read.PublicUserReader;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 键集扫描与批量投影处于同一只读快照；游标按已扫描位置推进，避免跳过异常条目后提前结束。 */
@Service
public class PublicContentListTransactionService {
    private final PublicContentMapper contents;
    private final PublicUserReader users;
    private final PublicContentAssembler assembler;
    private final PublicContentCursor cursors;
    public PublicContentListTransactionService(PublicContentMapper contents, PublicUserReader users, PublicContentAssembler assembler, PublicContentCursor cursors) {
        this.contents = contents; this.users = users; this.assembler = assembler; this.cursors = cursors;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ContentPageView list(PublicContentListQuery query) {
        var author = query.authorId() == null ? null : users.read(query.authorId());
        if (query.authorId() != null && author == null) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        var scanned = query.authorId() == null
                ? contents.listVisible(query.kind(), query.tagId(), local(query.snapshotAt()), local(query.lastFirstPublishedAt()), query.lastId(), query.limit() + 1)
                : contents.listVisibleByAuthor(query.authorId(), local(query.snapshotAt()), local(query.lastFirstPublishedAt()), query.lastId(), query.limit() + 1);
        boolean more = scanned.size() > query.limit();
        var rows = scanned.subList(0, Math.min(query.limit(), scanned.size()));
        if (rows.isEmpty()) return new ContentPageView(List.of(), null, false, query.snapshotAt());
        var revisions = rows.stream().map(PublishedRow::revisionId).toList();
        var authors = rows.stream().map(PublishedRow::authorId).distinct().toList();
        Map<String, List<AssetRow>> assets = new HashMap<>();
        for (var value : contents.findAssetsForRevisions(revisions)) {
            assets.computeIfAbsent(value.revisionId(), ignored -> new ArrayList<>()).add(
                    new AssetRow(value.assetId(), value.usageType(), value.position(), value.ownerId(), value.scene(), value.state()));
        }
        Map<String, List<TagRow>> tags = new HashMap<>();
        for (var value : contents.findTagsForRevisions(revisions)) {
            tags.computeIfAbsent(value.revisionId(), ignored -> new ArrayList<>()).add(new TagRow(value.id(), value.name(), value.description(), value.status(), value.version()));
        }
        var profiles = query.authorId() == null
                ? users.readAll(authors).stream().collect(Collectors.toMap(value -> value.id(), Function.identity()))
                : Map.of(query.authorId(), author);
        var counts = contents.countVisibleByAuthors(authors).stream().collect(Collectors.toMap(AuthorCount::authorId, AuthorCount::visibleCount));
        List<PublicContentSummaryView> items = new ArrayList<>();
        for (var row : rows) {
            // 只跳过纯投影识别的数据异常；所有数据库/批量读取故障均在循环外向上返回503。
            try {
                assembler.requirePublicRevision(row);
                var media = assembler.media(row, assets.getOrDefault(row.revisionId(), List.of()));
                var labels = assembler.tags(tags.getOrDefault(row.revisionId(), List.of()));
                items.add(PublicContentSummaryView.from(assembler.content(row, media, labels, profiles.get(row.authorId()), counts.getOrDefault(row.authorId(), 0L))));
            } catch (BusinessException ex) {
                if (ex.errorCode() != ErrorCode.RESOURCE_NOT_FOUND && ex.errorCode() != ErrorCode.DEPENDENCY_UNAVAILABLE) throw ex;
            }
        }
        var last = rows.get(rows.size() - 1);
        String next = more ? cursors.next(query, last.firstPublishedAt().toInstant(ZoneOffset.UTC), last.id()) : null;
        return new ContentPageView(items, next, more, query.snapshotAt());
    }
    private LocalDateTime local(Instant value) { return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
