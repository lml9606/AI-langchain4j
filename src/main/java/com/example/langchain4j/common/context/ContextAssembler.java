package com.example.langchain4j.common.context;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.TokenEstimator;
import com.example.langchain4j.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文预算装配器：把“各块各自截断”换成“一次统一分配”。
 *
 * 算法只有四步，顺序固定：
 * 1. 强制块（system/query/answer）先扣，不参与竞争；
 * 2. 可分配块按保底各拿一份，内容不足保底的只拿实际大小；
 * 3. 剩余预算按权重在“仍有缺口”的块之间迭代分配，被封顶的块退出下一轮；
 * 4. 最后剩余全部给弹性块（历史），没有弹性块就记为空闲额度。
 *
 * 超预算时的降级顺序写死在本类里（记忆 → 摘要 → 最旧历史 → 工具 → 证据 → 检索尾部），
 * 不开放配置：这个顺序配错会直接造成回答质量事故，属于代码级约束而不是运营参数。
 */
@Slf4j
@Component
public class ContextAssembler {

    /** 降级顺序：价值从低到高，预算不足时先牺牲排在前面的块。 */
    private static final List<BlockType> DEGRADE_ORDER = List.of(
            BlockType.MEMORY, BlockType.SUMMARY, BlockType.HISTORY,
            BlockType.TOOLS, BlockType.EVIDENCE, BlockType.RETRIEVAL);

    /** 片段之间的分隔符开销，按 1 token 估算，避免多片段拼接时轻微超预算。 */
    private static final int SEGMENT_SEPARATOR_TOKENS = 1;

    private final Map<ContextScene, ContextProfile> profiles;
    private final ContextProfile fallbackProfile;

    /**
     * 从配置构建各场景预算档位；未单独配置的场景使用 default-total-tokens。
     *
     * @param properties 全局配置；传 null 时退化为 8000 token 的默认档位，保证构造不会失败
     */
    public ContextAssembler(AgentProperties properties) {
        AgentProperties.Context context = properties == null ? null : properties.getContext();
        this.profiles = Map.copyOf(buildProfiles(context));
        this.fallbackProfile = buildFallback(context);
    }

    /**
     * 执行一次预算分配。
     *
     * @param scene  调用场景，决定总预算和块权重
     * @param blocks 本次实际存在的块；不存在的块不参与分配，预算自动让给其他块
     * @return 分配结果，包含裁剪后的内容、各块用量和丢弃说明
     * @throws BizException 强制块本身超出预算时抛出，提示用户缩短输入
     */
    public Allocation allocate(ContextScene scene, List<ContextBlock> blocks) {
        ContextProfile profile = profiles.getOrDefault(scene, fallbackProfile);

        // 步骤1：合并输入块（同类型只保留第一次出现，避免调用点重复传导致预算被重复扣）
        Map<BlockType, List<String>> segments = merge(blocks);
        Map<BlockType, Integer> cost = measure(segments);

        Map<BlockType, Integer> give = new EnumMap<>(BlockType.class);
        int remaining = profile.totalTokens();

        // 步骤2：强制块先扣；装不下说明输入本身超限，直接失败而不是静默截断
        for (Map.Entry<BlockType, Integer> entry : cost.entrySet()) {
            if (entry.getKey().mandatory()) {
                give.put(entry.getKey(), entry.getValue());
                remaining -= entry.getValue();
            }
        }
        if (remaining <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "输入内容过长（预算 " + profile.totalTokens() + " token），请缩短问题后重试");
        }

        // 步骤3：可分配块按保底拿第一份；内容不足保底的只拿实际大小
        List<BlockType> allocatable = new ArrayList<>();
        for (Map.Entry<BlockType, Integer> entry : cost.entrySet()) {
            BlockType type = entry.getKey();
            if (type.mandatory()) {
                continue;
            }
            allocatable.add(type);
            int floor = Math.min(profile.minimumOf(type), entry.getValue());
            give.put(type, floor);
            remaining -= floor;
        }

        // 步骤4：保底之和超预算时，按固定顺序回退，回退到不为负为止
        if (remaining < 0) {
            remaining = degrade(give, remaining);
        }

        // 步骤5：剩余按权重迭代分配，直到分完或所有块都吃满
        remaining = distributeByWeight(give, cost, allocatable, remaining, profile);

        // 步骤6：剩余全部给弹性块（历史），没有弹性块则记为 slack
        remaining = fillElastic(give, cost, allocatable, remaining);

        // 步骤7：按各自额度裁剪内容，记录丢弃明细
        Map<BlockType, List<String>> kept = new EnumMap<>(BlockType.class);
        Map<BlockType, Integer> used = new EnumMap<>(BlockType.class);
        Map<BlockType, String> drops = new EnumMap<>(BlockType.class);
        for (Map.Entry<BlockType, List<String>> entry : segments.entrySet()) {
            BlockType type = entry.getKey();
            SliceOutcome outcome = sliceSegments(type, entry.getValue(), give.getOrDefault(type, 0));
            if (!outcome.kept().isEmpty()) {
                kept.put(type, outcome.kept());
                used.put(type, outcome.usedTokens());
            }
            if (outcome.droppedSegments() > 0 || outcome.truncated()) {
                drops.put(type, outcome.describe());
            }
        }

        int totalUsed = used.values().stream().mapToInt(Integer::intValue).sum();
        Allocation allocation = new Allocation(scene, kept, used, drops,
                totalUsed, profile.totalTokens());
        log.info(allocation.logLine());
        return allocation;
    }

    /** 合并同类型块：后出现的同类型内容被忽略，避免重复计入预算。 */
    private Map<BlockType, List<String>> merge(List<ContextBlock> blocks) {
        Map<BlockType, List<String>> merged = new EnumMap<>(BlockType.class);
        if (blocks == null) {
            return merged;
        }
        for (ContextBlock block : blocks) {
            if (block != null && !block.isEmpty()) {
                merged.putIfAbsent(block.type(), block.segments());
            }
        }
        return merged;
    }

    /** 计算每块的 token 成本，含片段间分隔符。 */
    private Map<BlockType, Integer> measure(Map<BlockType, List<String>> segments) {
        Map<BlockType, Integer> cost = new EnumMap<>(BlockType.class);
        segments.forEach((type, list) -> cost.put(type, tokensOf(list)));
        return cost;
    }

    /** 片段列表的 token 成本：正文估算 + 分隔符开销。 */
    private int tokensOf(List<String> segments) {
        int tokens = 0;
        for (int i = 0; i < segments.size(); i++) {
            tokens += TokenEstimator.estimate(segments.get(i));
            if (i > 0) {
                tokens += SEGMENT_SEPARATOR_TOKENS;
            }
        }
        return tokens;
    }

    /**
     * 按固定顺序回退保底，直到缺口补齐。
     *
     * @param give    当前分配表，会被就地修改
     * @param deficit 负数，表示还差多少预算
     * @return 回退后的剩余预算（>=0 表示已补齐）
     */
    private int degrade(Map<BlockType, Integer> give, int deficit) {
        for (BlockType type : DEGRADE_ORDER) {
            if (deficit >= 0) {
                break;
            }
            int have = give.getOrDefault(type, 0);
            if (have <= 0) {
                continue;
            }
            int cut = Math.min(have, -deficit);
            give.put(type, have - cut);
            deficit += cut;
        }
        if (deficit < 0) {
            // 走完降级顺序仍然不够，说明强制块已经吃满预算，只能拒绝请求
            throw new BizException(ErrorCode.BAD_REQUEST, "输入内容过长，请缩短问题后重试");
        }
        return deficit;
    }

    /**
     * 剩余预算按权重在仍有缺口的块之间分配。
     *
     * 每轮用固定池子按权重切分，被封顶的块退出下一轮，未分完的额度继续下一轮；
     * 这样内容很少的块不会占着额度浪费，内容很多的块也能吃到最后一轮。
     */
    private int distributeByWeight(Map<BlockType, Integer> give, Map<BlockType, Integer> cost,
                                   List<BlockType> allocatable, int remaining,
                                   ContextProfile profile) {
        List<BlockType> hungry = allocatable.stream()
                .filter(type -> cost.getOrDefault(type, 0) > give.getOrDefault(type, 0))
                .toList();
        while (remaining > 0 && !hungry.isEmpty()) {
            int pool = remaining;
            long totalWeight = hungry.stream()
                    .mapToLong(type -> Math.max(0, profile.weightOf(type)))
                    .sum();
            if (totalWeight <= 0) {
                break;
            }
            int distributed = 0;
            List<BlockType> nextRound = new ArrayList<>();
            for (BlockType type : hungry) {
                int demand = cost.getOrDefault(type, 0) - give.getOrDefault(type, 0);
                long raw = pool * Math.max(0, profile.weightOf(type)) / totalWeight;
                int add = (int) Math.min(raw, demand);
                give.merge(type, add, Integer::sum);
                distributed += add;
                if (demand - add > 0) {
                    nextRound.add(type);
                }
            }
            if (distributed <= 0) {
                break;
            }
            remaining -= distributed;
            hungry = nextRound;
        }
        return remaining;
    }

    /** 把剩余预算交给弹性块；多个弹性块按顺序依次吃。 */
    private int fillElastic(Map<BlockType, Integer> give, Map<BlockType, Integer> cost,
                            List<BlockType> allocatable, int remaining) {
        for (BlockType type : allocatable) {
            if (!type.elastic() || remaining <= 0) {
                continue;
            }
            int demand = cost.getOrDefault(type, 0) - give.getOrDefault(type, 0);
            int add = Math.min(remaining, Math.max(0, demand));
            give.merge(type, add, Integer::sum);
            remaining -= add;
        }
        return remaining;
    }

    /** 按额度裁剪一组片段：能整条保留就整条保留，最后一条按 token 切片。 */
    private SliceOutcome sliceSegments(BlockType type, List<String> segments, int budgetTokens) {
        return type.keepTail()
                ? sliceFromTail(segments, budgetTokens)
                : sliceFromHead(segments, budgetTokens);
    }

    /** 从头部保留：检索/证据按重要度降序传入，保留前缀即保留最重要的部分。 */
    private SliceOutcome sliceFromHead(List<String> segments, int budgetTokens) {
        List<String> kept = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        int consumed = 0;
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            int separator = kept.isEmpty() ? 0 : SEGMENT_SEPARATOR_TOKENS;
            int segmentCost = TokenEstimator.estimate(segment);
            if (used + separator + segmentCost <= budgetTokens) {
                kept.add(segment);
                used += separator + segmentCost;
                consumed = i + 1;
                continue;
            }
            // 当前片装不下：尝试切片保住一部分，剩余片段整体丢弃
            int left = budgetTokens - used - separator;
            String sliced = TextSlicer.sliceToTokens(segment, left);
            if (!sliced.isBlank()) {
                kept.add(sliced);
                used += separator + TokenEstimator.estimate(sliced);
                truncated = true;
                consumed = i + 1;
            }
            break;
        }
        return new SliceOutcome(kept, used, segments.size() - consumed, truncated);
    }

    /** 从尾部保留：历史按时间正序传入，预算不足时丢弃最旧的前缀。 */
    private SliceOutcome sliceFromTail(List<String> segments, int budgetTokens) {
        Deque<String> kept = new ArrayDeque<>();
        int used = 0;
        boolean truncated = false;
        int consumed = 0;
        for (int i = segments.size() - 1; i >= 0; i--) {
            String segment = segments.get(i);
            int separator = kept.isEmpty() ? 0 : SEGMENT_SEPARATOR_TOKENS;
            int segmentCost = TokenEstimator.estimate(segment);
            if (used + separator + segmentCost <= budgetTokens) {
                kept.addFirst(segment);
                used += separator + segmentCost;
                consumed++;
                continue;
            }
            // 最新一条都装不下时保留它的结尾，避免整段历史直接丢失；
            // 更早的消息不做切片，半条旧对话价值低，直接整条丢弃。
            if (kept.isEmpty()) {
                String sliced = tailOf(segment, budgetTokens);
                if (!sliced.isBlank()) {
                    kept.addFirst(sliced);
                    used += TokenEstimator.estimate(sliced);
                    truncated = true;
                    consumed++;
                }
            }
            break;
        }
        return new SliceOutcome(new ArrayList<>(kept), used, segments.size() - consumed, truncated);
    }

    /** 从尾部按 token 预算截取，保证保留的是最新内容；预算为 0 时返回空串。 */
    private String tailOf(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        if (TokenEstimator.estimate(text) <= maxTokens) {
            return text;
        }
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (TokenEstimator.estimate(text.substring(text.length() - mid)) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low <= 0 ? "" : text.substring(text.length() - low).stripLeading();
    }

    /** 从配置构建场景档位表。 */
    private static Map<ContextScene, ContextProfile> buildProfiles(AgentProperties.Context context) {
        Map<ContextScene, ContextProfile> result = new EnumMap<>(ContextScene.class);
        if (context == null || context.getScenes() == null) {
            return result;
        }
        context.getScenes().forEach((scene, config) -> {
            if (scene == null || config == null) {
                return;
            }
            int total = config.getTotalTokens() > 0
                    ? config.getTotalTokens() : context.getDefaultTotalTokens();
            result.put(scene, new ContextProfile(total, config.getWeights(), config.getMinimums()));
        });
        return result;
    }

    /** 构建未单独配置场景使用的默认档。 */
    private static ContextProfile buildFallback(AgentProperties.Context context) {
        int total = context == null ? 8000 : context.getDefaultTotalTokens();
        return ContextProfile.of(total);
    }

    /** 单个块的裁剪结果，供丢弃统计和日志使用。 */
    private record SliceOutcome(List<String> kept, int usedTokens,
                                int droppedSegments, boolean truncated) {

        /** 生成可读的丢弃说明：整条丢弃记 "-N"，尾部截断记 "truncated"。 */
        String describe() {
            if (droppedSegments > 0 && truncated) {
                return "truncated+" + droppedSegments;
            }
            if (truncated) {
                return "truncated";
            }
            return "-" + droppedSegments;
        }
    }
}
