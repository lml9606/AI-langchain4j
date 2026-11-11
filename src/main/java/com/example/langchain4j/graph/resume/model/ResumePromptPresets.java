package com.example.langchain4j.graph.resume.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历自由优化预设提示词：5 个维度 × 5 条，供前端直接选择或自定义。
 * <p>
 * 与定向优化共用同一套「不编造 / 事实字段逐字保留」硬约束，预设只改变优化侧重点。
 */
public final class ResumePromptPresets {

    public record Preset(String name, String prompt) {
    }

    private static final Map<String, List<Preset>> PRESETS = build();

    private ResumePromptPresets() {
    }

    public static Map<String, List<Preset>> all() {
        return PRESETS;
    }

    private static Map<String, List<Preset>> build() {
        Map<String, List<Preset>> map = new LinkedHashMap<>();

        map.put("通用优化", List.of(
                new Preset("全面润色",
                        "全面优化简历的表达，使语言更精炼、专业，突出个人亮点，增强可读性。保持内容真实性，不编造经历。"),
                new Preset("精简凝练",
                        "精简冗长的描述，删除冗余信息，保留核心要点。使简历更紧凑，突出关键信息，控制在合理篇幅内。"),
                new Preset("扩展完善",
                        "在不编造的前提下，扩展过于简略的描述，补充背景信息和成果细节，使每段经历更充实、更具说服力。"),
                new Preset("逻辑优化",
                        "优化各章节和条目的排列顺序，按重要性和相关性重组，使简历逻辑更清晰、重点更突出。"),
                new Preset("关键词强化",
                        "在保持自然表达的前提下，适当融入行业通用关键词和技术术语，增强简历在 ATS 系统中的匹配度。")
        ));

        map.put("技术深度", List.of(
                new Preset("技术细节强化",
                        "深化技术描述，补充技术选型理由、架构设计思路、技术难点攻克过程，体现技术深度和问题解决能力。"),
                new Preset("技术栈突出",
                        "突出核心技术栈的使用深度和广度，明确各技术在项目中的具体应用场景和掌握程度。"),
                new Preset("架构设计凸显",
                        "强调系统架构设计能力，补充架构图、技术选型、扩展性考量等内容，体现架构思维。"),
                new Preset("性能优化突出",
                        "突出性能优化经验，补充优化前后的具体指标对比、优化思路和技术手段，用数据说话。"),
                new Preset("开源贡献展示",
                        "如有开源项目或技术分享经历，优化其描述，突出技术影响力和社区贡献，增强技术形象。")
        ));

        map.put("管理能力", List.of(
                new Preset("团队管理突出",
                        "强化团队管理经验，补充团队规模、人员培养、绩效管理等内容，体现领导力和团队协作能力。"),
                new Preset("项目管理强化",
                        "突出项目管理能力，补充项目规划、进度把控、风险管理、跨部门协调等经验，展现项目推动力。"),
                new Preset("决策能力展示",
                        "强调关键决策经验，补充决策背景、权衡考量、执行结果，体现战略思维和决策能力。"),
                new Preset("资源整合能力",
                        "突出资源整合和跨团队协作经验，补充协调多方资源、推动跨部门合作的具体案例。"),
                new Preset("人才培养经验",
                        "强化人才培养和团队建设经验，补充招聘、培训、导师制度等内容，体现团队建设能力。")
        ));

        map.put("业绩成果", List.of(
                new Preset("量化成果突出",
                        "强化业绩数据，将定性描述转化为定量指标（用户增长、性能提升、成本节约等），用数据说话。"),
                new Preset("商业价值凸显",
                        "突出工作成果的商业价值，补充对公司营收、用户增长、市场份额的具体贡献。"),
                new Preset("影响力展示",
                        "强调工作的影响范围和深度，补充受益用户数、影响团队规模、业务覆盖面等内容。"),
                new Preset("创新成果突出",
                        "突出创新性工作和突破性成果，补充创新点、难度、行业首创性等内容，体现创新能力。"),
                new Preset("奖项荣誉强化",
                        "如有奖项或荣誉，优化其描述，补充获奖背景、评选标准、含金量，增强说服力。")
        ));

        map.put("转行求职", List.of(
                new Preset("可迁移技能突出",
                        "强化可迁移技能（沟通、分析、问题解决等），淡化行业特定经验，突出通用能力和学习能力。"),
                new Preset("相关经验挖掘",
                        "深挖现有经历中与目标方向相关的内容，重新组织表达，建立与目标岗位的关联。"),
                new Preset("学习能力展示",
                        "突出快速学习和适应新领域的能力，补充自学经历、技能转换案例，降低转行顾虑。"),
                new Preset("动机与热情传递",
                        "在自我评价等章节适当补充转行动机和对新领域的热情，增强求职诚意和决心。"),
                new Preset("跨界经验优势",
                        "将跨行业经验转化为优势，强调复合背景带来的独特视角和创新思维。")
        ));

        return Collections.unmodifiableMap(map);
    }
}
