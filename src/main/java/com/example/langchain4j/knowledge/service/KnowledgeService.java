package com.example.langchain4j.knowledge.service;

import com.example.langchain4j.auth.security.UserContext;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Throwables;
import com.example.langchain4j.common.util.TokenEstimator;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.knowledge.dto.request.KnowledgeUpdateRequest;
import com.example.langchain4j.knowledge.dto.response.KnowledgeFileImportResponse;
import com.example.langchain4j.knowledge.dto.response.KnowledgeHealthResponse;
import com.example.langchain4j.knowledge.dto.response.KnowledgeSearchResponse;
import com.example.langchain4j.knowledge.embedding.EmbeddingService;
import com.example.langchain4j.knowledge.embedding.SparseEmbeddingService;
import com.example.langchain4j.knowledge.entity.KnowledgeChunk;
import com.example.langchain4j.knowledge.entity.KnowledgeDocument;
import com.example.langchain4j.knowledge.mapper.KnowledgeChunkMapper;
import com.example.langchain4j.knowledge.mapper.KnowledgeDocumentMapper;
import com.example.langchain4j.knowledge.parser.KnowledgeFileParser;
import com.example.langchain4j.knowledge.retrieval.VectorSearchService;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.UUID;

/**
 * 知识服务：父子分块导入（父块整章不嵌入 + 子块嵌入落库）、检索与知识治理
 * （批量导入/更新删除/分块可配/健康度统计）。
 */
@Slf4j
@Service
public class KnowledgeService {

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 10;
    /** 分块大小防御边界：过小碎片化、过大单块语义稀释（入口另有 maxDocumentChars 总量拦截） */
    private static final int MIN_CHUNK_SIZE = 200;
    private static final int MAX_CHUNK_SIZE = 1200;
    /** 父块大小防御边界：过小失去整章语义、过大装配时超出上下文预算 */
    private static final int MIN_PARENT_SIZE = 500;
    private static final int MAX_PARENT_SIZE = 8000;
    /** 子块 token 估算上限（与字符上限双约束）：中文按 1 字 1 token 的保守估算，给嵌入模型留安全余量 */
    private static final int MIN_CHUNK_TOKEN_LIMIT = 256;
    private static final int MAX_CHUNK_TOKEN_LIMIT = 2048;
    /** 逻辑文档 ID 列宽（schema: VARCHAR(64)） */
    private static final int MAX_DOCUMENT_ID_LENGTH = 64;
    /** 文档来源列宽（schema: ai_document.source VARCHAR(512)） */
    private static final int MAX_SOURCE_LENGTH = 512;
    /** 文档主表状态：空闲（可接受导入） */
    private static final String STATUS_READY = "READY";
    /** 段落边界：空行切分优先级最高，保证段落语义块完整 */
    private static final String PARAGRAPH_BOUNDARY = "\n\n";
    /** 句末边界字符：段落/行边界都找不到时退而求其次，避免把句子拦腰截断 */
    private static final String BOUNDARY_CHARS = "。！？；";
    /** 块类型：父块（整章上下文，不嵌入）/ 子块（嵌入供检索） */
    private static final String TYPE_PARENT = "PARENT";
    private static final String TYPE_CHILD = "CHILD";
    /** 子块正文前的标题路径分隔符：把父标题拼进子块，保证父标题也能被检索命中 */
    private static final String TITLE_SEPARATOR = "｜";
    /** 父块标题路径分隔符：保留从章节到小节的层级，供检索与上下文装配使用 */
    private static final String TITLE_PATH_SEPARATOR = " > ";
    /** 父块目标区间默认值：自适应选层用，不写死某一级标题 */
    private static final int DEFAULT_PARENT_TARGET_MIN = 800;
    private static final int DEFAULT_PARENT_TARGET_MAX = 2000;
    /** 允许的长尾比例：超过父块硬上限的小节占比不高于该值时，仍可选用该层 */
    private static final double PARENT_OVER_RATIO_LIMIT = 0.25;
    /** title 列宽（schema: VARCHAR(256)），超长标题路径防御性截断 */
    private static final int MAX_TITLE_LENGTH = 256;
    /** 强结构标题行：Markdown 标题（#~######）与中文章节（第X章/节/篇/部）；Markdown 优先按层级处理 */
    private static final Pattern STRONG_TITLE_LINE = Pattern.compile(
            "^[ \\t]*(?:#{1,6}[ \\t]+\\S|第[一二三四五六七八九十百千\\d]+[章节篇部]).*",
            Pattern.MULTILINE);
    /** 多级编号标题（1.1 / 2.3.1）：仅当文档无强结构标题时启用（单级 1. 与有序列表同形，不在本级） */
    private static final Pattern MULTI_LEVEL_TITLE_LINE = Pattern.compile(
            "^[ \\t]*\\d+(?:\\.\\d+)+[、.．]?[ \\t]*\\S.*", Pattern.MULTILINE);
    /** 单级编号标题（1、/ 1.）：最后兜底——文档连多级编号都没有时，编号行大概率就是章节 */
    private static final Pattern SINGLE_LEVEL_TITLE_LINE = Pattern.compile(
            "^[ \\t]*\\d+[、.．][ \\t]*\\S.*", Pattern.MULTILINE);
    /** Markdown 标题行：语义切分子块时作为结构边界（不依赖空行也能断开） */
    private static final Pattern MARKDOWN_HEADING_LINE = Pattern.compile("^#{1,6}[ \\t]+\\S.*");
    /** 整行加粗标签：如 **标准答案**、**追问 1：...**；与后续答案合并，避免标签单独成块 */
    private static final Pattern STRONG_LABEL_LINE = Pattern.compile("^\\*\\*[^*]+\\*\\*$");

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final EmbeddingService embeddingService;
    private final SparseEmbeddingService sparseEmbeddingService;
    private final VectorSearchService vectorSearchService;
    private final KnowledgeFileParser fileParser;
    private final AgentProperties properties;
    private final ThreadPoolTaskExecutor importExecutor;
    private final TransactionTemplate transactionTemplate;

    public KnowledgeService(KnowledgeChunkMapper knowledgeChunkMapper,
                            KnowledgeDocumentMapper knowledgeDocumentMapper,
                            EmbeddingService embeddingService,
                            SparseEmbeddingService sparseEmbeddingService,
                            VectorSearchService vectorSearchService,
                            KnowledgeFileParser fileParser,
                            AgentProperties properties,
                            @Qualifier("knowledgeImportExecutor") ThreadPoolTaskExecutor importExecutor,
                            PlatformTransactionManager transactionManager) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
        this.embeddingService = embeddingService;
        this.sparseEmbeddingService = sparseEmbeddingService;
        this.vectorSearchService = vectorSearchService;
        this.fileParser = fileParser;
        this.properties = properties;
        this.importExecutor = importExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 语义检索（topK 有界防御），检索失败降级为空列表由上游处理 */
    public KnowledgeSearchResponse search(String query, int topK) {
        int bounded = Math.min(Math.max(topK, 1), MAX_TOP_K);
        List<KnowledgeChunk> chunks = vectorSearchService.search(query, bounded);
        List<KnowledgeSearchResponse.Chunk> result = chunks.stream()
                .map(c -> new KnowledgeSearchResponse.Chunk(c.getContent(), c.getMetadata()))
                .toList();
        return KnowledgeSearchResponse.of(result);
    }

    /** 使用默认 topK 执行知识检索。 */
    public KnowledgeSearchResponse search(String query) {
        return search(query, DEFAULT_TOP_K);
    }

    /**
     * 导入长文档并发布为一个新版本。
     *
     * 发布模型：写块阶段不改 active_version，新版本对检索完全不可见；写完后只更新文档主表一行指针完成切换。
     * 因此导入失败不会出现"查到半成品"，切换成本也与文档块数无关。
     *
     * @param documentId 逻辑文档 ID；空则生成 UUID（新文档）
     * @param version    文档版本；空则自动取当前版本 + 1，显式值必须大于当前版本
     * @return 落库总块数（父块 + 子块）
     */
    public int importDocument(String content, String metadata, Integer chunkSize, Integer chunkOverlap,
                              String documentId, Integer version) {
        long startedAt = System.currentTimeMillis();
        // 0. 入口清洗与参数归一化：后续所有阶段只使用 cleaned 和 bounded 后的参数
        String cleaned = sanitize(content);
        if (cleaned.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "content 清洗后为空");
        }
        checkDocumentSize(cleaned);
        int childSize = boundedChunkSize(chunkSize);
        int parentSize = boundedParentChunkSize();
        ImportSettings settings = new ImportSettings(
                parentSize,
                boundedParentTargetMin(parentSize),
                boundedParentTargetMax(parentSize),
                childSize,
                boundedChunkOverlap(chunkOverlap, childSize),
                boundedChunkTokenLimit());

        String userId = UserContext.require();
        String resolvedDocumentId = resolveDocumentId(documentId);
        // 文档聚合根必须先存在：active_version 是版本分配的基准，也是导入互斥的锚点
        KnowledgeDocument document = ensureDocument(resolvedDocumentId, userId);
        int resolvedVersion = resolveVersion(document, userId, version);
        // 抢占导入权：同一文档同一时刻只允许一个任务写新版本，避免并发导入互相覆盖
        claimImport(resolvedDocumentId, userId);
        log.info("[knowledge] import start, documentId={}, version={}, activeVersion={}, chars={}, parentSize={}, "
                        + "childSize={}, overlap={}, childTokenLimit={}",
                resolvedDocumentId, resolvedVersion, document.getActiveVersion(), cleaned.length(),
                settings.parentSize(), settings.childSize(), settings.overlap(), settings.childTokenLimit());

        try {
            int chunks = writeVersion(cleaned, metadata, settings, resolvedDocumentId, resolvedVersion, userId);
            // 发布：只改一行指针；此刻新版本才对检索可见，旧版本继续保留在库里等待 GC 回收
            publishVersion(resolvedDocumentId, userId, resolvedVersion, contentHash(cleaned), extractSource(metadata));
            log.info("[knowledge] document published, documentId={}, version={}, chunks={}, cost={}ms",
                    resolvedDocumentId, resolvedVersion, chunks, System.currentTimeMillis() - startedAt);
            return chunks;
        } catch (RuntimeException e) {
            // 失败版本只是未发布的草稿：删掉即可，active_version 不变，旧版本对外仍然可读
            log.error("[knowledge] document import failed, documentId={}, version={}, reason={}",
                    resolvedDocumentId, resolvedVersion, Throwables.rootMessage(e));
            cleanupFailedImport(resolvedDocumentId, resolvedVersion, userId);
            releaseImport(resolvedDocumentId, userId);
            throw e;
        }
    }

    /** 一次导入的分块参数快照：入口归一化后固定，避免下游重复读配置导致同一文档前后不一致 */
    private record ImportSettings(int parentSize, int parentTargetMin, int parentTargetMax,
                                  int childSize, int overlap, int childTokenLimit) {
    }

    /**
     * 写入一个完整版本的父子块（不发布）：数据对检索不可见，失败由调用方按版本清理。
     *
     * @return 落库总块数（父块 + 子块）
     */
    private int writeVersion(String cleaned, String metadata, ImportSettings settings,
                             String documentId, int version, String userId) {
        // 步骤1：标题树 → 父块。父块保留整段上下文但不嵌入；超长父块会继续按段落/句子续切。
        long splitStartedAt = System.currentTimeMillis();
        List<ParentSection> sections = splitParents(cleaned, settings.parentSize(),
                settings.parentTargetMin(), settings.parentTargetMax());
        log.info("[knowledge] parent split done, documentId={}, version={}, parents={}, cost={}ms",
                documentId, version, sections.size(), System.currentTimeMillis() - splitStartedAt);

        // 步骤2：父块 → 语义子块 → 双向量。嵌入是易失败阶段，必须放在落库前，失败时数据库零残留。
        List<KnowledgeChunk> parents = new ArrayList<>(sections.size());
        List<KnowledgeChunk> children = new ArrayList<>();
        List<Integer> childrenPerParent = new ArrayList<>(sections.size());
        long embedStartedAt = System.currentTimeMillis();
        for (int p = 0; p < sections.size(); p++) {
            ParentSection section = sections.get(p);
            KnowledgeChunk parent = new KnowledgeChunk();
            parent.setContent(section.body());
            parent.setChunkType(TYPE_PARENT);
            parent.setTitle(section.title());
            parent.setMetadata(withChunkInfo(metadata, p + 1, sections.size()));
            parent.setUserId(userId);
            parent.setDocumentId(documentId);
            parent.setVersion(version);
            parents.add(parent);
            List<String> pieces = splitChildren(section.body(), section.title(),
                    settings.childSize(), settings.overlap(), settings.childTokenLimit());
            log.debug("[knowledge] parent prepared, index={}/{}, title={}, parentChars={}, children={}",
                    p + 1, sections.size(), section.title(), section.body().length(), pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                KnowledgeChunk child = new KnowledgeChunk();
                String piece = pieces.get(i);
                // 每个子块都带完整标题路径：既提升检索命中，也让续块脱离原文顺序后仍能定位所属小节
                String childText = section.title() + TITLE_SEPARATOR + piece;
                child.setContent(childText);
                // 双向量写入：稠密（语义，BGE-M3）+ 稀疏（词权重，jieba hashing），检索端 RRF 融合
                child.setEmbedding(embeddingService.embed(childText));
                child.setSparseVector(sparseEmbeddingService.embed(childText));
                child.setChunkType(TYPE_CHILD);
                child.setTitle(section.title());
                child.setMetadata(withChunkInfo(metadata, i + 1, pieces.size()));
                child.setUserId(userId);
                child.setDocumentId(documentId);
                child.setVersion(version);
                children.add(child);
            }
            childrenPerParent.add(pieces.size());
        }
        log.info("[knowledge] children embedded, documentId={}, version={}, parents={}, children={}, cost={}ms",
                documentId, version, parents.size(), children.size(),
                System.currentTimeMillis() - embedStartedAt);

        // 步骤3：父块逐个落库拿自增 ID；子块必须在父块之后落库，才能回填 parent_id。
        // children 是按父块顺序连续追加的，因此用 cursor 做一次线性回填，避免额外的 Map 查找。
        long persistStartedAt = System.currentTimeMillis();
        log.info("[knowledge] persistence start, documentId={}, version={}, parents={}, children={}",
                documentId, version, parents.size(), children.size());
        int cursor = 0;
        for (int p = 0; p < parents.size(); p++) {
            knowledgeChunkMapper.insert(parents.get(p));
            for (int i = 0; i < childrenPerParent.get(p); i++) {
                children.get(cursor + i).setParentId(parents.get(p).getId());
            }
            cursor += childrenPerParent.get(p);
        }
        if (!children.isEmpty()) {
            knowledgeChunkMapper.insertBatch(children);
        }
        log.info("[knowledge] persistence done, documentId={}, version={}, cost={}ms",
                documentId, version, System.currentTimeMillis() - persistStartedAt);
        return parents.size() + children.size();
    }

    /** 清理导入失败时可能已落库的半成品版本（尽力而为，清理失败不覆盖原始异常） */
    private void cleanupFailedImport(String documentId, int version, String userId) {
        try {
            knowledgeChunkMapper.deleteDocumentVersion(documentId, version, userId);
        } catch (Exception cleanupError) {
            log.warn("[knowledge] failed import cleanup failed, documentId={}, version={}, reason={}",
                    documentId, version, cleanupError.getMessage());
        }
    }

    /**
     * 文件上传导入：逐个文件“解析 → 父子分块落库”，单文件失败不中断整批（与 batchImport 同语义）。
     *
     * 与 batchImport 的差异：文件在 HTTP 线程内同步解析（PDF/Word 解析为 CPU 密集但单文件几秒内完成），
     * 文件名写入 metadata.source 便于溯源，块归属当前登录用户（私有库）。
     *
     * @param files 上传文件列表（上限复用 agent.knowledge.batch-max-documents 配置）
     * @return 逐文件导入结果（fileName / chunks / ok / error），失败项供重试
     */
    public KnowledgeFileImportResponse uploadFiles(List<MultipartFile> files) {
        return uploadFiles(files, null, null);
    }

    /**
     * 文件上传导入（可指定逻辑文档与版本）。
     *
     * 多文件请求不能共用同一个 documentId；单文件可传 documentId/version 实现同文档版本更新。
     */
    public KnowledgeFileImportResponse uploadFiles(List<MultipartFile> files, String documentId, Integer version) {
        int maxFiles = Math.max(1, properties.getKnowledge().getBatchMaxDocuments());
        if (files.size() > maxFiles) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单次最多上传 " + maxFiles + " 个文件");
        }
        if (files.size() > 1 && (documentId != null || version != null)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "多文件上传不能共用 documentId/version");
        }
        List<KnowledgeFileImportResponse.FileResult> results = new ArrayList<>(files.size());
        int imported = 0;
        int failed = 0;
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String fileName = file != null ? file.getOriginalFilename() : null;
            try {
                if (file == null || file.isEmpty()) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "文件内容为空");
                }
                String text = fileParser.extract(fileName, file.getBytes());
                String metadata = "{\"source\":\"file:" + fileName + "\"}";
                int chunks = importDocument(text, metadata, null, null, documentId, version);
                results.add(new KnowledgeFileImportResponse.FileResult(i, fileName, chunks, true, null));
                imported++;
            } catch (Exception e) {
                log.warn("[knowledge] file import {} failed: {}", fileName, e.getMessage());
                results.add(new KnowledgeFileImportResponse.FileResult(i, fileName, 0, false,
                        Throwables.rootMessage(e)));
                failed++;
            }
        }
        return new KnowledgeFileImportResponse(files.size(), imported, failed, results);
    }

    /**
     * 更新知识块：归属校验（仅本人私有块可改，公共库块 403）→ 重嵌 → 更新。
     * SQL 层再按 user_id 过滤（双保险，防 TOCTOU 越权）。
     */
    public void updateChunk(long id, KnowledgeUpdateRequest request) {
        String userId = UserContext.require();
        KnowledgeChunk existing = knowledgeChunkMapper.selectById(id, userId);
        requirePrivateChunk(existing);
        String content = sanitize(request.getContent());
        if (content.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "content 清洗后为空");
        }
        checkDocumentSize(content);
        if (TYPE_PARENT.equals(existing.getChunkType())) {
            updateParentAndRebuildChildren(existing, content, request.getMetadata(), userId);
        } else {
            updateChildAndSyncParent(existing, content, request.getMetadata(), userId);
        }
        log.info("[knowledge] chunk updated, id={}", id);
    }

    /** 删除知识块：仅本人私有块可删（公共库块 403），SQL 层 user_id 过滤双保险 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteChunk(long id) {
        String userId = UserContext.require();
        KnowledgeChunk existing = knowledgeChunkMapper.selectById(id, userId);
        requirePrivateChunk(existing);
        if (TYPE_PARENT.equals(existing.getChunkType())) {
            int removedChildren = knowledgeChunkMapper.deleteChildrenByParentId(id, userId);
            if (knowledgeChunkMapper.deleteById(id, userId) == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, "知识块不存在或已被删除");
            }
            log.info("[knowledge] parent chunk deleted with children, id={}, children={}", id, removedChildren);
            return;
        }
        String oldBody = childBody(existing);
        if (knowledgeChunkMapper.deleteById(id, userId) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识块不存在或已被删除");
        }
        if (existing.getParentId() != null) {
            KnowledgeChunk parent = knowledgeChunkMapper.selectParentById(existing.getParentId(), userId);
            if (parent != null) {
                syncParentAfterChildDelete(parent, oldBody, userId);
            }
        }
        log.info("[knowledge] chunk deleted, id={}", id);
    }

    /** 仅允许维护本人私有块；公共库块由运营侧维护，避免普通用户改动共享知识。 */
    private void requirePrivateChunk(KnowledgeChunk existing) {
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识块不存在或不可见");
        }
        if (existing.getUserId() == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "公共库知识块仅运营侧可维护");
        }
    }

    /**
     * 更新父块：父块保存整章上下文，子块是检索单元。
     * 父块正文变化后必须重建子块，否则检索仍会命中旧子块、生成仍会拿到旧父块。
     */
    private void updateParentAndRebuildChildren(KnowledgeChunk parent, String content,
                                                String metadata, String userId) {
        // 远程 embedding 必须在事务外完成：先算好全部新子块，再进入短事务替换父子块。
        List<KnowledgeChunk> newChildren = buildChildren(parent, content, metadata, userId);
        if (newChildren.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "父块内容无法切出有效子块");
        }
        transactionTemplate.executeWithoutResult(status -> {
            if (knowledgeChunkMapper.updateParentContent(parent.getId(), content, metadata, userId) == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, "父块不存在或已被删除");
            }
            int removedChildren = knowledgeChunkMapper.deleteChildrenByParentId(parent.getId(), userId);
            knowledgeChunkMapper.insertBatch(newChildren);
            log.info("[knowledge] parent updated and children rebuilt, id={}, removedChildren={}, newChildren={}",
                    parent.getId(), removedChildren, newChildren.size());
        });
    }

    /**
     * 更新子块：子块重嵌后，父块是命中子块时装配的整章上下文，必须同步替换对应正文。
     * 如果父块原文找不到旧子块正文（历史数据或人工改过父块），退化为按当前兄弟子块重建父块内容。
     */
    private void updateChildAndSyncParent(KnowledgeChunk child, String content,
                                          String metadata, String userId) {
        String oldBody = childBody(child);
        String newBody = childBody(content, child.getTitle());
        if (newBody.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "子块正文不能为空");
        }
        String childText = withChildTitle(child.getTitle(), newBody);
        float[] denseVector = embeddingService.embed(childText);
        String sparseVector = sparseEmbeddingService.embed(childText);
        KnowledgeChunk update = new KnowledgeChunk();
        update.setId(child.getId());
        update.setContent(childText);
        // 双向量同步重算：内容变更后稠密/稀疏任一不重算都会造成该通道命中错位
        update.setEmbedding(denseVector);
        update.setSparseVector(sparseVector);
        update.setMetadata(metadata);
        update.setUserId(userId);
        transactionTemplate.executeWithoutResult(status -> {
            if (knowledgeChunkMapper.updateContent(update) == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, "知识块不存在或已被删除");
            }
            if (child.getParentId() == null) {
                return;
            }
            KnowledgeChunk parent = knowledgeChunkMapper.selectParentById(child.getParentId(), userId);
            if (parent == null) {
                return;
            }
            String updatedParentContent = replaceOnce(parent.getContent(), oldBody, newBody);
            if (updatedParentContent == null) {
                updatedParentContent = mergeChildBodies(
                        knowledgeChunkMapper.selectChildrenByParentId(parent.getId(), userId));
            }
            updateParentContentOrThrow(parent, updatedParentContent, userId);
        });
    }

    /** 子块删除后同步父块：没有兄弟子块则删除父块；否则移除旧正文，定位失败时按剩余子块重建。 */
    private void syncParentAfterChildDelete(KnowledgeChunk parent, String oldBody, String userId) {
        List<KnowledgeChunk> remaining = knowledgeChunkMapper.selectChildrenByParentId(parent.getId(), userId);
        if (remaining == null || remaining.isEmpty()) {
            if (knowledgeChunkMapper.deleteById(parent.getId(), userId) == 0) {
                log.warn("[knowledge] parent already missing after child delete, parentId={}", parent.getId());
            }
            return;
        }
        String updated = replaceOnce(parent.getContent(), oldBody, "");
        if (updated == null || updated.isBlank()) {
            updated = mergeChildBodies(remaining);
        }
        updateParentContentOrThrow(parent, updated, userId);
    }

    /** 父块内容落库；空内容视为无有效上下文，删除父块，避免生成链路拿到空父块。 */
    private void updateParentContentOrThrow(KnowledgeChunk parent, String content, String userId) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            knowledgeChunkMapper.deleteChildrenByParentId(parent.getId(), userId);
            if (knowledgeChunkMapper.deleteById(parent.getId(), userId) == 0) {
                log.warn("[knowledge] parent already missing after content sync, parentId={}", parent.getId());
            }
            return;
        }
        if (knowledgeChunkMapper.updateParentContent(parent.getId(), normalized, parent.getMetadata(), userId) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "父块不存在或已被删除");
        }
    }

    /** 按父块正文重建子块；先完成嵌入再写库，失败时事务回滚不会留下半成品。 */
    private List<KnowledgeChunk> buildChildren(KnowledgeChunk parent, String body,
                                               String metadata, String userId) {
        int childSize = boundedChunkSize(null);
        int overlap = boundedChunkOverlap(null, childSize);
        int tokenLimit = boundedChunkTokenLimit();
        List<String> pieces = splitChildren(body, parent.getTitle(), childSize, overlap, tokenLimit);
        List<KnowledgeChunk> children = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            String childText = withChildTitle(parent.getTitle(), pieces.get(i));
            KnowledgeChunk child = new KnowledgeChunk();
            child.setContent(childText);
            child.setEmbedding(embeddingService.embed(childText));
            child.setSparseVector(sparseEmbeddingService.embed(childText));
            child.setChunkType(TYPE_CHILD);
            child.setTitle(parent.getTitle());
            child.setMetadata(withChunkInfo(metadata, i + 1, pieces.size()));
            child.setUserId(userId);
            child.setDocumentId(parent.getDocumentId());
            child.setVersion(parent.getVersion());
            child.setParentId(parent.getId());
            children.add(child);
        }
        return children;
    }

    /** 子块存储文本带标题前缀；这里统一去掉前缀后再比较/重建父块正文。 */
    private String childBody(KnowledgeChunk chunk) {
        return childBody(chunk.getContent(), chunk.getTitle());
    }

    /** 去掉子块内容开头的标题前缀，返回纯正文用于父块重建。 */
    private String childBody(String content, String title) {
        String text = content == null ? "" : content.trim();
        if (title != null && !title.isBlank()) {
            String prefix = title + TITLE_SEPARATOR;
            if (text.startsWith(prefix)) {
                return text.substring(prefix.length()).trim();
            }
        }
        return text;
    }

    /** 给子块正文加标题前缀；标题为空时保持正文原样。 */
    private String withChildTitle(String title, String body) {
        if (title == null || title.isBlank()) {
            return body == null ? "" : body;
        }
        return title + TITLE_SEPARATOR + (body == null ? "" : body);
    }

    /** 文本中替换一次子块正文；找不到返回 null，由调用方走重建兜底。 */
    private String replaceOnce(String text, String oldPart, String newPart) {
        if (text == null || oldPart == null || oldPart.isBlank()) {
            return null;
        }
        int index = text.indexOf(oldPart);
        if (index < 0) {
            return null;
        }
        return text.substring(0, index) + (newPart == null ? "" : newPart)
                + text.substring(index + oldPart.length());
    }

    /** 兄弟子块重建父块正文：按 id 顺序拼接，并去掉相邻子块的重叠部分，避免上下文重复。 */
    private String mergeChildBodies(List<KnowledgeChunk> children) {
        if (children == null || children.isEmpty()) {
            return "";
        }
        StringBuilder merged = new StringBuilder();
        for (KnowledgeChunk child : children) {
            String body = childBody(child);
            if (body.isBlank()) {
                continue;
            }
            if (merged.length() == 0) {
                merged.append(body);
                continue;
            }
            String current = merged.toString();
            int overlap = overlapLength(current, body);
            if (overlap > 0) {
                merged.append(body.substring(overlap));
            } else {
                merged.append(PARAGRAPH_BOUNDARY).append(body);
            }
        }
        return merged.toString().trim();
    }

    /** 相邻子块最大重叠长度（子块切分时的 overlap 保留策略）。 */
    private int overlapLength(String left, String right) {
        int max = Math.min(left.length(), right.length());
        for (int i = max; i > 0; i--) {
            if (left.regionMatches(left.length() - i, right, 0, i)) {
                return i;
            }
        }
        return 0;
    }

    /** 知识库健康度：当前用户可见范围（公共库 + 本人私有库）聚合统计 */
    public KnowledgeHealthResponse health() {
        return KnowledgeHealthResponse.from(knowledgeChunkMapper.healthStats(UserContext.require()));
    }

    /**
     * 长文档切分：回退窗口内按“段落 > 行尾 > 句末标点”优先级找边界（保证语义块完整），
     * 无边界则硬切；chunkOverlap 保留跨块边界的语义连续。
     * 父子分块中作为子块切分复用（父块内按段落切最小检索单元）。
     */
    List<String> split(String content, int chunkSize, int chunkOverlap) {
        if (content.length() <= chunkSize) {
            return List.of(content);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            if (end < content.length()) {
                // 回退窗口 [start + chunkSize/2, end)：按优先级找切分终点，避免切断段落/句子
                int boundaryEnd = lastBoundaryEnd(content, start + chunkSize / 2, end);
                if (boundaryEnd > 0) {
                    end = boundaryEnd;
                }
            }
            String piece = content.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= content.length()) {
                break;
            }
            // 下一块起点回退 overlap；至少前进 1，防边界计算异常导致死循环
            start = Math.max(end - chunkOverlap, start + 1);
        }
        return pieces;
    }

    /**
     * 在 [from, to) 范围内按优先级找切分终点（exclusive）：
     * 段落边界（空行）> 行尾（换行）> 句末标点；均无返回 -1（调用方硬切）。
     */
    private int lastBoundaryEnd(String content, int from, int to) {
        int paragraph = content.lastIndexOf(PARAGRAPH_BOUNDARY, to - 1);
        if (paragraph >= from) {
            // 钳制到 to：空行起点为 to-1 时终点会越界 1 个字符，避免首/尾碎片被下一块重复消费
            return Math.min(paragraph + PARAGRAPH_BOUNDARY.length(), to);
        }
        int line = content.lastIndexOf('\n', to - 1);
        if (line >= from) {
            return line + 1;
        }
        int punct = lastBoundary(content, from, to);
        return punct >= 0 ? punct + 1 : -1;
    }

    /** 在 [from, to) 范围内找最后一个句末标点位置；无则返回 -1 */
    private int lastBoundary(String content, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (BOUNDARY_CHARS.indexOf(content.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 子块语义切分：先按空行 / Markdown 标题拆成语义单元，代码块作为一个整体保留；
     * 单元只在预算内合并，单个单元超预算时才递归回退到句子 / 硬切。
     *
     * 字符上限与 token 估算上限同时生效。token 使用 {@link TokenEstimator} 的保守估算，
     * 目的是防止长英文、代码块或中文密排文本把嵌入输入撑爆；它不是模型 tokenizer 的精确替代。
     */
    List<String> splitSemantic(String content, int chunkSize, int chunkOverlap, int maxTokens) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String text = content.trim();
        int tokenLimit = Math.max(1, maxTokens);
        // TokenEstimator 对 CJK 按 1 字 1 token 估算，因此字符上限不超过 token 上限即可形成保守兜底
        int charLimit = Math.max(1, Math.min(chunkSize, tokenLimit));
        if (fits(text, charLimit, tokenLimit)) {
            return List.of(text);
        }

        List<String> parts = expandSemanticUnits(text, charLimit, tokenLimit);
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String carry = "";
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String candidate = current.isEmpty() ? part : current + "\n\n" + part;
            if (!current.isEmpty() && !fits(candidate, charLimit, tokenLimit)) {
                String flushed = current.toString().trim();
                if (!flushed.isEmpty()) {
                    pieces.add(flushed);
                }
                carry = overlapSuffix(flushed, chunkOverlap, charLimit, tokenLimit);
                current.setLength(0);
            }
            if (current.isEmpty() && !carry.isEmpty()) {
                if (fits(carry + "\n\n" + part, charLimit, tokenLimit)) {
                    current.append(carry).append("\n\n");
                }
                // 带上重叠后放不下时直接丢弃 carry，保证单块不超预算
                carry = "";
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(part);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString().trim());
        }
        return pieces;
    }

    /** 父块内切子块：预扣标题前缀 token，保证拼上标题后的最终 childText 仍在预算内 */
    private List<String> splitChildren(String body, String title, int maxChars, int overlap, int maxTokens) {
        String titlePrefix = title == null || title.isBlank() ? "" : title + TITLE_SEPARATOR;
        int prefixTokens = TokenEstimator.estimate(titlePrefix);
        // 预留 16 token 余量吸收估算误差与分隔符拼接差异
        int bodyTokenLimit = Math.max(64, maxTokens - prefixTokens - 16);
        int bodyCharLimit = Math.min(maxChars, bodyTokenLimit);
        int bodyOverlap = Math.min(overlap, bodyCharLimit / 2);
        List<String> pieces = splitSemantic(body, bodyCharLimit, bodyOverlap, bodyTokenLimit);
        log.debug("[knowledge] child split done, titleChars={}, bodyChars={}, bodyCharLimit={}, "
                        + "bodyTokenLimit={}, overlap={}, children={}",
                titlePrefix.length(), body == null ? 0 : body.length(), bodyCharLimit,
                bodyTokenLimit, bodyOverlap, pieces.size());
        return pieces;
    }

    /** 把语义单元展开为不超过预算的切片：正常单元原样保留，超大单元才回退到句子/硬切 */
    private List<String> expandSemanticUnits(String content, int charLimit, int tokenLimit) {
        List<String> parts = new ArrayList<>();
        for (String unit : semanticUnits(content)) {
            if (fits(unit, charLimit, tokenLimit)) {
                parts.add(unit);
            } else {
                parts.addAll(split(unit, charLimit, 0));
            }
        }
        return parts;
    }

    /**
     * 结构语义单元识别：空行结束普通段落；Markdown 标题开新单元；围栏代码块（``` / ~~~）跨空行保持完整；
     * 整行加粗标签（标准答案/追问等）与其后答案合并，避免问题标签与答案被切到不同块。
     * 不引入额外元数据，仅用文本自身结构决定切分边界。
     */
    private List<String> semanticUnits(String content) {
        List<String> units = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        // 状态机：fence 标识当前是否在代码块内；labelBlock 标识正在吸收“标签 + 答案”；
        // hasBody 区分“只有连续标签”和“标签后已经出现真正正文”，只有后者才允许结束当前块。
        String fence = null;
        boolean labelBlock = false;
        boolean hasBody = false;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            if (fence != null) {
                // 代码块内不识别空行、标题或加粗标签，直到遇到同类围栏才整体提交。
                block.append(line).append('\n');
                if (trimmed.startsWith(fence)) {
                    flushSemanticUnit(block, units);
                    fence = null;
                    labelBlock = false;
                    hasBody = false;
                }
                continue;
            }
            String openedFence = fenceMarker(trimmed);
            if (openedFence != null) {
                // 标签后的代码示例属于答案；标签前已有正文时才断开
                if (!labelBlock) {
                    flushSemanticUnit(block, units);
                }
                block.append(line).append('\n');
                hasBody = true;
                fence = openedFence;
                continue;
            }
            if (trimmed.isEmpty()) {
                // 标签块跨空行继续吸收答案；普通段落仍在空行处结束
                if (labelBlock) {
                    block.append('\n');
                } else {
                    flushSemanticUnit(block, units);
                }
                continue;
            }
            if (MARKDOWN_HEADING_LINE.matcher(trimmed).matches()) {
                // 新标题一定开启新语义单元；先提交上一块，避免标题被拼进上一段答案。
                flushSemanticUnit(block, units);
                labelBlock = false;
                hasBody = false;
                block.append(line).append('\n');
                continue;
            }
            if (STRONG_LABEL_LINE.matcher(trimmed).matches()) {
                // 连续标签（如 **面试官追问** + **追问 1**）保留在同一块；已有答案才结束上一块
                if (!block.isEmpty() && hasBody) {
                    flushSemanticUnit(block, units);
                }
                labelBlock = true;
                block.append(line).append('\n');
                continue;
            }
            block.append(line).append('\n');
            hasBody = true;
        }
        flushSemanticUnit(block, units);
        return units;
    }

    /** 围栏代码块起始标记；普通行返回 null */
    private String fenceMarker(String trimmedLine) {
        if (trimmedLine.startsWith("```")) {
            return "```";
        }
        return trimmedLine.startsWith("~~~") ? "~~~" : null;
    }

    /** 提交当前语义单元（空块忽略） */
    private void flushSemanticUnit(StringBuilder block, List<String> units) {
        String unit = block.toString().trim();
        block.setLength(0);
        if (!unit.isEmpty()) {
            units.add(unit);
        }
    }

    /** 字符与 token 双预算判断 */
    private boolean fits(CharSequence text, int charLimit, int tokenLimit) {
        return text != null
                && text.length() <= charLimit
                && TokenEstimator.estimate(text.toString()) <= tokenLimit;
    }

    /**
     * 取上一块末尾的完整句子作为语义重叠：从最后一个句末标点向前找句子起点，
     * 不截半个词或半句话；找不到完整句子就放弃重叠。
     * 重叠量不超过单块预算的 1/3，避免重叠反而挤掉正文。
     */
    private String overlapSuffix(String text, int overlap, int charLimit, int tokenLimit) {
        if (text == null || text.isBlank() || overlap <= 0) {
            return "";
        }
        int maxCarry = Math.max(1, Math.min(charLimit / 3, tokenLimit / 3));
        int carryChars = Math.min(overlap, maxCarry);
        int end = lastSentenceEndExclusive(text);
        if (end <= 0) {
            return "";
        }
        int start = sentenceStartAtOrAfter(text, Math.max(0, end - carryChars), end);
        if (start < 0) {
            return "";
        }
        String suffix = text.substring(start, end).trim();
        int maxCarryTokens = Math.max(1, tokenLimit / 3);
        // token 预算仍可能因英文/代码密度超标：从左侧整句丢弃，绝不截断句子
        while (!suffix.isEmpty() && TokenEstimator.estimate(suffix) > maxCarryTokens) {
            start = sentenceStartAtOrAfter(text, start + 1, end);
            if (start < 0) {
                return "";
            }
            suffix = text.substring(start, end).trim();
        }
        return suffix;
    }

    /** 最后一个完整句子的结束位置（exclusive，含句末标点后的引号/括号）；没有句末标点返回 -1 */
    private int lastSentenceEndExclusive(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (!isSentenceEnd(text, i)) {
                continue;
            }
            int end = i + 1;
            while (end < text.length() && isClosingPunctuation(text.charAt(end))) {
                end++;
            }
            return end;
        }
        return -1;
    }

    /**
     * 在 [from, limit) 内找第一个完整句子起点：前一个非空白字符必须是句末标点，
     * 保证重叠不会从半个句子开始。
     */
    private int sentenceStartAtOrAfter(String text, int from, int limit) {
        for (int i = Math.max(0, from); i < limit; i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                continue;
            }
            if (i == 0) {
                return 0;
            }
            int prev = i - 1;
            while (prev >= 0 && Character.isWhitespace(text.charAt(prev))) {
                prev--;
            }
            while (prev >= 0 && isClosingPunctuation(text.charAt(prev))) {
                prev--;
            }
            if (prev < 0 || isSentenceEnd(text, prev)) {
                return i;
            }
        }
        return -1;
    }

    /** 句末标点：中文标点直接判定；英文标点要求后接空白/结尾，避免把小数点或代码符号当句末 */
    private boolean isSentenceEnd(String text, int index) {
        char current = text.charAt(index);
        if ("。！？；".indexOf(current) >= 0) {
            return true;
        }
        if (current != '.' && current != '!' && current != '?' && current != ';') {
            return false;
        }
        int next = index + 1;
        while (next < text.length() && isClosingPunctuation(text.charAt(next))) {
            next++;
        }
        return next >= text.length() || Character.isWhitespace(text.charAt(next));
    }

    /** 句末标点后可能紧跟的闭合符号，提取完整句子时一并带上 */
    private boolean isClosingPunctuation(char current) {
        return "”’\"'」』】）)》〉".indexOf(current) >= 0;
    }

    /**
     * 入口大小拦截：单篇内容超过字符上限直接拒绝导入/更新。
     * 拒绝而非截断：静默截断会丢内容且用户无感知，显式报错让用户自行拆分后重导。
     */
    private void checkDocumentSize(String content) {
        int maxChars = Math.max(1, properties.getKnowledge().getMaxDocumentChars());
        if (content.length() > maxChars) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "内容过长：" + content.length() + " 字符，单篇上限 " + maxChars + " 字符，请拆分后导入");
        }
    }

    /** 分块大小防御边界：200~1200（过小碎片化、过大单块语义稀释） */
    private int boundedChunkSize(Integer requestChunkSize) {
        int configured = properties.getKnowledge().getChunkSize();
        int size = requestChunkSize != null ? requestChunkSize : configured;
        return Math.min(Math.max(size, MIN_CHUNK_SIZE), MAX_CHUNK_SIZE);
    }

    /** 父块大小防御边界：500~8000（仅走配置，章节边界优先于大小硬切） */
    private int boundedParentChunkSize() {
        int configured = properties.getKnowledge().getParentChunkSize();
        return Math.min(Math.max(configured, MIN_PARENT_SIZE), MAX_PARENT_SIZE);
    }

    /** 父块目标区间下限：不小于分块最小防御边界且不超过父块硬上限，避免把父块切得过碎或目标失效 */
    private int boundedParentTargetMin(int parentSize) {
        int configured = properties.getKnowledge().getParentTargetMin();
        return Math.min(Math.max(configured, MIN_PARENT_SIZE), parentSize);
    }

    /** 父块目标区间上限：不超过父块硬上限，保证目标区间与硬约束一致 */
    private int boundedParentTargetMax(int parentSize) {
        int configured = properties.getKnowledge().getParentTargetMax();
        return Math.min(Math.max(configured, MIN_PARENT_SIZE), parentSize);
    }

    /** 分块重叠防御边界：0 ~ chunkSize/2（负值归零，过大反而稀释块语义） */
    private int boundedChunkOverlap(Integer requestOverlap, int chunkSize) {
        int configured = properties.getKnowledge().getChunkOverlap();
        int overlap = requestOverlap != null ? requestOverlap : configured;
        return Math.min(Math.max(overlap, 0), chunkSize / 2);
    }

    /** 子块 token 估算上限：256~2048，防配置过小碎片化或过大超出嵌入模型预算 */
    private int boundedChunkTokenLimit() {
        int configured = properties.getKnowledge().getChunkTokenLimit();
        return Math.min(Math.max(configured, MIN_CHUNK_TOKEN_LIMIT), MAX_CHUNK_TOKEN_LIMIT);
    }

    /** 归一化逻辑文档 ID：未传生成新 UUID；显式值做长度防御（对应 VARCHAR(64)） */
    private String resolveDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = documentId.trim();
        if (trimmed.length() > MAX_DOCUMENT_ID_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "documentId 长度不能超过 " + MAX_DOCUMENT_ID_LENGTH);
        }
        return trimmed;
    }

    /**
     * 解析版本：基于"当前发布版本"与"库中最大块版本"取较大者作为基准再 +1。
     * 基准同时看两边：active_version 决定对外可见版本，最大块版本用于跳过失败导入残留的草稿号，
     * 避免同一版本号被两次导入复用。
     */
    private int resolveVersion(KnowledgeDocument document, String userId, Integer version) {
        Integer maxChunkVersion = knowledgeChunkMapper.selectMaxVersion(document.getDocumentId(), userId);
        int base = Math.max(document.getActiveVersion() == null ? 0 : document.getActiveVersion(),
                maxChunkVersion == null ? 0 : maxChunkVersion);
        if (version == null) {
            return base + 1;
        }
        if (version < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 必须大于 0");
        }
        if (version <= base) {
            throw new BizException(ErrorCode.BAD_REQUEST, "version 必须大于当前版本 " + base);
        }
        return version;
    }

    /** 文档聚合根：不存在则幂等创建；active_version=0 表示尚无成功导入版本（导入期间对检索不可见） */
    private KnowledgeDocument ensureDocument(String documentId, String userId) {
        KnowledgeDocument existing = knowledgeDocumentMapper.selectByDocumentId(documentId, userId);
        if (existing != null) {
            return existing;
        }
        KnowledgeDocument fresh = new KnowledgeDocument();
        fresh.setDocumentId(documentId);
        fresh.setUserId(userId);
        fresh.setActiveVersion(0);
        fresh.setStatus(STATUS_READY);
        knowledgeDocumentMapper.insertIfAbsent(fresh);
        KnowledgeDocument created = knowledgeDocumentMapper.selectByDocumentId(documentId, userId);
        if (created == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文档元数据初始化失败");
        }
        return created;
    }

    /** 抢占导入权：并发导入同一文档时只有一个任务进入，其余快速失败（不排队、不重复嵌入） */
    private void claimImport(String documentId, String userId) {
        int updated = knowledgeDocumentMapper.startImport(
                documentId, userId, properties.getKnowledge().getImportStaleMinutes());
        if (updated == 0) {
            throw new BizException(ErrorCode.DOCUMENT_IMPORTING, "该文档正在导入中，请稍后重试");
        }
    }

    /** 发布版本：只更新一行指针；影响行数为 0 说明导入权已被接管，本次写入作废 */
    private void publishVersion(String documentId, String userId, int version,
                                String contentHash, String source) {
        if (knowledgeDocumentMapper.activate(documentId, userId, version, contentHash, source) == 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "文档版本发布失败，导入状态可能已被接管");
        }
    }

    /** 释放导入权并记录失败：active_version 不变，旧版本继续对外可读 */
    private void releaseImport(String documentId, String userId) {
        try {
            knowledgeDocumentMapper.markImportFailed(documentId, userId);
        } catch (Exception e) {
            log.warn("[knowledge] mark import failed error, documentId={}, reason={}", documentId, e.getMessage());
        }
    }

    /** 内容指纹（SHA-256）：用于识别无变更重传；计算失败不阻断导入 */
    private String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            log.warn("[knowledge] content hash unavailable: {}", e.getMessage());
            return null;
        }
    }

    /** 从 metadata JSON 提取 source 写入文档主表（运维定位用）；非 JSON 或缺失返回 null */
    private String extractSource(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = JsonUtil.fromJson(metadata, new TypeReference<>() {
            });
            Object source = parsed == null ? null : parsed.get("source");
            if (source == null) {
                return null;
            }
            String value = String.valueOf(source);
            return value.length() <= MAX_SOURCE_LENGTH ? value : value.substring(0, MAX_SOURCE_LENGTH);
        } catch (Exception e) {
            return null;
        }
    }

    /** 元数据追加分块信息（chunkIndex/chunkCount）：原 JSON 对象合并，非 JSON 原文保留在 raw 字段 */
    private String withChunkInfo(String metadata, int index, int total) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (metadata != null && !metadata.isBlank()) {
            try {
                Map<String, Object> parsed = JsonUtil.fromJson(metadata, new TypeReference<>() {
                });
                if (parsed != null) {
                    map.putAll(parsed);
                }
            } catch (Exception e) {
                map.put("raw", metadata);
            }
        }
        map.put("chunkIndex", index);
        map.put("chunkCount", total);
        return JsonUtil.toJson(map);
    }

    /**
     * 父子分块第一层：按文档结构自适应选择父块层级（整段上下文，不嵌入），不写死 H3。
     *
     * - 逐层统计小节长度，优先选择中位数落在目标区间、且超长比例可接受的层级；
     * - 选中层是父块边界；更深层标题内容并入当前父块，不再单独成块；
     * - 更浅层标题只提供标题路径；若其自有导语，导语仍单独保留，避免丢内容；
     * - 父块 title 保存从顶级章节到父块边界的完整路径；
     * - 唯一的一级标题视为文档标题，不进入子标题路径；其自有导语仍保留为前言父块；
     * - 没有可识别标题的文档按长度窗口切分；只有标题、没有正文的叶节点仍保留，避免丢内容；
     * - 围栏代码块中的 # 注释不当作标题；
     * - 父块超预算 → 复用 {@link #splitSemantic} 按软标题/段落/句子续切，续块保持同一 title。
     *
     */
    List<ParentSection> splitParents(String content, int parentSize) {
        return splitParents(content, parentSize, DEFAULT_PARENT_TARGET_MIN,
                Math.min(DEFAULT_PARENT_TARGET_MAX, parentSize));
    }

    List<ParentSection> splitParents(String content, int parentSize, int targetMin, int targetMax) {
        int boundedTargetMin = Math.max(1, targetMin);
        int boundedTargetMax = Math.max(boundedTargetMin, Math.min(targetMax, parentSize));
        long startedAt = System.currentTimeMillis();
        List<Heading> headings = headings(content);
        log.debug("[knowledge] parent split start, chars={}, parentSize={}, headings={}",
                content.length(), parentSize, headings.size());
        // 没有任何可识别标题：整篇按长度硬切，所有父块共享同一个兜底标题。
        if (headings.isEmpty()) {
            String title = deriveTitle(content);
            List<ParentSection> sections = splitSemantic(content, parentSize, 0, parentSize).stream()
                    .map(piece -> new ParentSection(title, piece))
                    .toList();
            log.debug("[knowledge] parent split done without headings, title={}, parents={}, cost={}ms",
                    title, sections.size(), System.currentTimeMillis() - startedAt);
            return sections;
        }
        Heading documentTitle = documentTitle(headings);
        int parentLevel = chooseParentLevel(content, headings, boundedTargetMin, boundedTargetMax, parentSize);
        log.debug("[knowledge] parent level selected, parentLevel={}, targetMin={}, targetMax={}",
                parentLevel, boundedTargetMin, boundedTargetMax);
        List<ParentSection> sections = new ArrayList<>();
        // 首个标题前的正文没有标题路径，用兜底标题单独保留；否则这段前言会丢失。
        String preamble = content.substring(0, headings.get(0).start()).trim();
        if (!preamble.isEmpty() && hasMeaningfulBody(preamble)) {
            log.debug("[knowledge] parent preamble kept, chars={}, title={}", preamble.length(), deriveTitle(preamble));
            addSections(sections, deriveTitle(preamble), preamble, parentSize);
        }

        List<Heading> path = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            // 维护祖先链：同级/更浅标题说明上一棵子树结束，先弹栈；再压入当前标题。
            // 例如 H2(a) -> H3(a1) -> H2(b)：处理 b 时会连续弹出 a1、a，得到新的路径 [b]。
            while (!path.isEmpty() && path.get(path.size() - 1).level() >= heading.level()) {
                path.remove(path.size() - 1);
            }
            path.add(heading);

            // 比选中层更深的标题不产生父块，但其文本和正文会被所属父块整体包含。
            if (heading.level() > parentLevel) {
                continue;
            }

            // 比选中层更浅的标题只取自己的直接正文，遇到下一标题即结束；
            // 选中层标题则一直扩展到下一个同级/更浅标题，因此其下所有深层内容都会留在同一个父块中。
            int end = parentSectionEnd(headings, i, parentLevel, content.length());
            String body = content.substring(heading.start(), end).trim();
            if (body.isEmpty()) {
                continue;
            }
            int bodyStart = nextLineStart(content, heading.start(), end);
            boolean hasOwnBody = hasMeaningfulBody(content.substring(bodyStart, end));
            boolean hasChild = i + 1 < headings.size() && headings.get(i + 1).level() > heading.level();
            // 比选中层更浅的纯容器只提供路径；选中层即使没有直接正文，也要把其下深层内容装进父块。
            if (heading.level() < parentLevel && !hasOwnBody && hasChild) {
                log.debug("[knowledge] parent container skipped, level={}, title={}",
                        heading.level(), headingPath(path, content, documentTitle));
                continue;
            }
            String title = headingPath(path, content, documentTitle);
            log.debug("[knowledge] parent section accepted, index={}/{}, level={}, ownBody={}, child={}, "
                            + "bodyChars={}, title={}",
                    i + 1, headings.size(), heading.level(), hasOwnBody, hasChild, body.length(), title);
            addSections(sections, title, body, parentSize);
        }
        log.debug("[knowledge] parent split done, headings={}, parentLevel={}, documentTitle={}, parents={}, cost={}ms",
                headings.size(), parentLevel, documentTitle != null, sections.size(),
                System.currentTimeMillis() - startedAt);
        return sections;
    }

    /**
     * 扫描标题行并标注层级。Markdown 标题用 # 数量作层级；非 Markdown 标题统一按同一层处理。
     * 按结构强度逐级回退：强结构（#/第X章）> 多级编号（1.1）> 单级编号（1.）；
     * 围栏代码块内的行不参与标题识别。
     */
    private List<Heading> headings(String content) {
        List<Heading> markdown = new ArrayList<>();
        List<Heading> strongChapters = new ArrayList<>();
        List<Heading> multiLevel = new ArrayList<>();
        List<Heading> singleLevel = new ArrayList<>();
        String fence = null;
        int lineStart = 0;
        while (lineStart <= content.length()) {
            int lineEnd = content.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = content.length();
            }
            String line = content.substring(lineStart, lineEnd);
            String trimmed = line.trim();
            if (fence != null) {
                if (trimmed.startsWith(fence)) {
                    fence = null;
                }
            } else {
                String openedFence = fenceMarker(trimmed);
                if (openedFence != null) {
                    fence = openedFence;
                } else if (MARKDOWN_HEADING_LINE.matcher(trimmed).matches()) {
                    int level = 0;
                    while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                        level++;
                    }
                    markdown.add(new Heading(lineStart, level));
                } else if (STRONG_TITLE_LINE.matcher(line).matches()) {
                    strongChapters.add(new Heading(lineStart, 2));
                } else if (MULTI_LEVEL_TITLE_LINE.matcher(line).matches()) {
                    multiLevel.add(new Heading(lineStart, 2));
                } else if (SINGLE_LEVEL_TITLE_LINE.matcher(line).matches()) {
                    singleLevel.add(new Heading(lineStart, 2));
                }
            }
            if (lineEnd >= content.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        if (!markdown.isEmpty()) {
            // 只有一个一级标题且正文用“第X章”这类标题时，把它当文档标题，退回章节标题切分
            if (markdown.size() == 1 && markdown.get(0).level() == 1 && !strongChapters.isEmpty()) {
                return strongChapters;
            }
            return markdown;
        }
        if (!strongChapters.isEmpty()) {
            return strongChapters;
        }
        if (!multiLevel.isEmpty()) {
            return multiLevel;
        }
        return singleLevel;
    }

    /** 唯一且后面还有更深标题的一级标题视为文档标题，只进入正文，不进入小节路径 */
    private Heading documentTitle(List<Heading> headings) {
        Heading first = headings.get(0);
        long h1Count = headings.stream().filter(heading -> heading.level() == 1).count();
        boolean hasDeeper = headings.stream().anyMatch(heading -> heading.level() > 1);
        // 唯一 H1 且后面还有更深标题时，它更像文档名而非章节：排除出父块路径，避免每个 title 都重复文档名。
        return first.level() == 1 && h1Count == 1 && hasDeeper ? first : null;
    }

    /**
     * 自适应选择父块层级：只决定“按哪一级标题切”，不在这里实际切块。
     *
     * 按 H2 -> H3 -> H4 ... 从浅到深逐层统计整篇文档：
     *
     * - 每个标题的长度 = 从标题开始到下一个同级/更浅标题之前的整段字符数，
     *       因此包含它下面的全部子标题和正文；
     * - 同一层少于 2 个标题时样本不足，跳过该层；
     * - 中位数落在 targetMin ~ targetMax，且长度超过 parentSize 的标题占比不超过 25%，
     *       该层才合格；
     * - 返回最浅的合格层，保留尽可能多的父块上下文；
     * - 所有层都不合格时，返回中位数离目标区间最近的一层；若所有层都样本不足，
     *       退回最深层。
     *
     * 目标区间只影响选层，不是父块硬约束；超长父块随后由 splitSemantic 继续切分。
     */
    private int chooseParentLevel(String content, List<Heading> headings,
                                  int targetMin, int targetMax, int parentSize) {
        int maxLevel = headings.stream().mapToInt(Heading::level).max().orElse(1);
        // 只有 H1 或没有更深标题时，没有可选的小节层级，直接按现有层级切。
        if (maxLevel <= 1) {
            return maxLevel;
        }
        Heading documentTitle = documentTitle(headings);
        int bestLevel = -1;
        int bestDistance = Integer.MAX_VALUE;
        // 关键：按“整篇文档的同一层标题”横向统计，不递归进入某一棵子树单独判断。
        // H2 合格就保留整章上下文；H2 不合适才继续尝试 H3、H4。
        for (int level = 2; level <= maxLevel; level++) {
            List<Integer> sizes = new ArrayList<>();
            for (int i = 0; i < headings.size(); i++) {
                Heading heading = headings.get(i);
                if (heading.level() != level) {
                    continue;
                }
                if (documentTitle != null && heading.start() == documentTitle.start()) {
                    continue;
                }
                // 当前候选层等于 heading.level()，parentSectionEnd 会跨过更深子标题，
                // 因此 size 是该标题整棵子树的长度，而不只是标题后的直接正文。
                int end = parentSectionEnd(headings, i, level, content.length());
                int size = end - heading.start();
                if (size > 0) {
                    sizes.add(size);
                }
            }
            // 单个样本无法代表整篇结构，也不能因为一个超长标题就决定下钻到更深层。
            if (sizes.size() < 2) {
                continue;
            }
            int median = median(sizes);
            int overCount = 0;
            for (int size : sizes) {
                if (size > parentSize) {
                    overCount++;
                }
            }
            double overRatio = overCount * 1.0 / sizes.size();
            // 中位数判断“典型章节是否合适”，超长比例判断“长尾是否还能接受”。
            // 从 H2 向 H3/H4 递增遍历，第一个合格层就是最浅合格层。
            if (median >= targetMin && median <= targetMax && overRatio <= PARENT_OVER_RATIO_LIMIT) {
                return level;
            }
            // 没有直接合格层时记录离目标区间最近的层级；距离相同保留先遇到的浅层。
            int distance = median < targetMin ? targetMin - median
                    : median > targetMax ? median - targetMax : 0;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestLevel = level;
            }
        }
        // 所有层都少于 2 个样本时，退回最深层，保证最终仍有父块边界可用。
        return bestLevel > 0 ? bestLevel : maxLevel;
    }

    /** 小节长度中位数：偶数取中间两个的平均值 */
    private int median(List<Integer> sizes) {
        List<Integer> sorted = new ArrayList<>(sizes);
        sorted.sort(Integer::compareTo);
        int size = sorted.size();
        return size % 2 == 1
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    /**
     * 计算当前标题父块的结束位置。
     *
     * 比选中层更浅的标题只提供路径，取自己的直接正文，遇到下一标题即结束；
     * 选中层是父块边界，必须一直扩展到下一个同级或更浅标题，不能在其子标题处提前结束。
     * 例如 parentLevel=3 时，H3 会一直包含到下一个 H3/H2/H1，其下 H4/H5 都属于该 H3 父块。
     */
    private int parentSectionEnd(List<Heading> headings, int index, int parentLevel, int contentLength) {
        Heading current = headings.get(index);
        for (int i = index + 1; i < headings.size(); i++) {
            Heading next = headings.get(i);
            if (current.level() == parentLevel) {
                if (next.level() <= parentLevel) {
                    return next.start();
                }
            } else {
                return next.start();
            }
        }
        return contentLength;
    }

    /** 生成“顶级章节 > 子章节 > 当前标题”的路径；文档标题不进入路径 */
    private String headingPath(List<Heading> path, String content, Heading documentTitle) {
        List<String> parts = new ArrayList<>();
        for (Heading heading : path) {
            if (documentTitle != null && heading.start() == documentTitle.start()) {
                continue;
            }
            String text = headingText(content, heading.start());
            if (!text.isEmpty()) {
                parts.add(text);
            }
        }
        // 当前标题就是文档标题（或没有可展示祖先）时，用自己的标题兜底
        if (parts.isEmpty()) {
            parts.add(headingText(content, path.get(path.size() - 1).start()));
        }
        String title = String.join(TITLE_PATH_SEPARATOR, parts);
        return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
    }

    /** 取标题行文本并去掉 Markdown 的 # 前缀，路径中保留可读标题 */
    private String headingText(String content, int start) {
        int end = content.indexOf('\n', start);
        String line = (end < 0 ? content.substring(start) : content.substring(start, end)).trim();
        int marker = 0;
        while (marker < line.length() && line.charAt(marker) == '#') {
            marker++;
        }
        return (marker > 0 ? line.substring(marker) : line).trim();
    }

    /** 标题行结束后的下一行起点；用于把标题行和它自己的正文分开 */
    private int nextLineStart(String content, int start, int limit) {
        int lineEnd = content.indexOf('\n', start);
        return lineEnd < 0 || lineEnd >= limit ? limit : lineEnd + 1;
    }

    /** 是否包含可检索正文：忽略空行和 Markdown 分隔线 */
    private boolean hasMeaningfulBody(String text) {
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !isHorizontalRule(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /** Markdown 水平分隔线（--- / *** / ___），不作为父块正文 */
    private boolean isHorizontalRule(String line) {
        if (line.length() < 3) {
            return false;
        }
        char first = line.charAt(0);
        if (first != '-' && first != '*' && first != '_') {
            return false;
        }
        for (int i = 1; i < line.length(); i++) {
            if (line.charAt(i) != first) {
                return false;
            }
        }
        return true;
    }

    /** 按父块预算切节（超长节续切为多个同 title 父块） */
    private void addSections(List<ParentSection> sections, String title, String body, int parentSize) {
        List<String> pieces = splitSemantic(body, parentSize, 0, parentSize);
        if (pieces.size() > 1) {
            // 续块仍使用同一 title，检索命中任一续块时可由父块组装逻辑找回相邻上下文。
            log.debug("[knowledge] parent section oversized, title={}, bodyChars={}, parentSize={}, pieces={}",
                    title, body.length(), parentSize, pieces.size());
        }
        for (String piece : pieces) {
            sections.add(new ParentSection(title, piece));
        }
    }

    /** 父块切分单元：title（紧凑标题，装配分组键）+ body（整章上下文） */
    record ParentSection(String title, String body) {
    }

    /** 标题行：start 为行首偏移，level 为 Markdown 层级（非 Markdown 标题统一为 2） */
    private record Heading(int start, int level) {
    }

    /** 无标题兜底标题：首行（非空且 ≤40 字）优先，否则取前 100 字截断 */
    private String deriveTitle(String content) {
        int end = content.indexOf('\n');
        String first = end < 0 ? content : content.substring(0, end);
        String trimmed = first.trim();
        if (!trimmed.isEmpty() && trimmed.length() <= 40) {
            return trimmed;
        }
        String body = content.trim();
        return body.length() > 100 ? body.substring(0, 100) : body;
    }

    /** 控制字符清洗 + 边界空白清理（嵌入前的防御性处理） */
    private String sanitize(String content) {
        // 保留换行/制表符：语义切分依赖段落与代码块结构；其余控制字符替换为空格
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").trim();
    }
}
