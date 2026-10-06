package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;

/**
 * 参数草稿状态机（R1.5）：只做「载入基线 vs 当前值」的<b>纯值比较</b>，零 JavaFX、零控件引用，
 * 因此可被无头单测直接驱动 —— 这是本步刻意的可测性切分：把「脏判定」从控件里剥出来。
 *
 * <h2>为什么放在 {@code app} 包</h2>
 * <p>「当前参数与已确认值是否不同」是<b>业务语义</b>，与怎么画无关：参数表单的历任宿主
 * （模态窗口壳与 Q6a 的非模态侧边栏）必须用<b>同一份</b>判定。放在 {@code gui}
 * 会把它绑回 JavaFX 生命周期（控件在、状态才在），于是"草稿"就没法在换容器时保住。</p>
 *
 * <h2>值语义：复用 {@link EffectiveParamsService#signatureOf(AppConfig)}</h2>
 * <p>{@code AppConfig} 是 Lombok {@code @Data} 可变对象，其 javadoc 明禁用 {@code equals} 当判定键
 * （值会随外部修改而变），{@code toString()} 同理不可靠；反射取字段则会被 Lombok 生成顺序绑住。
 * 故这里不另写一套比较，直接复用 Q1 已经收敛出来的<b>字段级内容签名</b>
 * （固定字段顺序 + 长度前缀；见 {@code EffectiveParamsService.signatureOf}）——
 * 全仓库只有这一个"参数值是否相同"的实现点。</p>
 *
 * <p><b>签名刻意排除的 4 个字段</b>（{@code inputFolder} / {@code outputFolder} /
 * {@code imageModeEnabled} / {@code openFolderAfterComplete}，见
 * {@link EffectiveParamsService#runtimeOnlyFields()}）都是运行时 / GUI 状态，<b>不参与脏判定</b>。
 * 这与语义恰好一致：它们本来就不是用户在参数面板里"编辑并确认"的东西，变了也不算脏；
 * 反过来说，凡是可持久化的提取参数都在签名里，改一个就必定变脏。</p>
 *
 * <h2>「保留草稿」（R1.5：折叠 = 收起面板并保留草稿）</h2>
 * <p>本类<b>没有</b> {@code discard()} 之类"清草稿"的入口，这不是遗漏 —— 保留草稿的正确实现就是
 * <b>什么都不做</b>：只要不调 {@link #commit(AppConfig)}，基线就不会被改写，于是"收起再展开"之后
 * {@link #isDirty(AppConfig)} 依旧为真（或者依旧为假，取决于用户改没改过），草稿不会丢。
 * 也就是说草稿的载体是「面板控件里的当前值 + 没被改写的基线」，本类只负责后者。
 * <b>因此 Q6a 做折叠/展开时，不要在收起路径里调 {@code commit} 或 {@code baseline}</b> ——
 * 那才会把草稿丢掉（commit 会把"未确认的编辑"就地升级成已确认值）。</p>
 *
 * <p>线程约束：无（与 {@code AppConfig} 一样按 GUI 线程使用）。</p>
 */
public final class ParamDraft {

    /**
     * 基线签名（= 载入初值时 / 上次提交时的参数快照）。
     *
     * <p>初值取 {@code signatureOf(null)} = "无生效参数"标记 —— 真实配置的签名至少含一个模式位字符，
     * 故与它必不相同，不会出现"没设过基线却判定为不脏"的假阴性。</p>
     */
    private String baseline = EffectiveParamsService.signatureOf(null);

    /**
     * 记录基线：把 {@code snapshot} 的值语义记为当前基线。参数面板在
     * {@code load(...)}（把初值写进控件<b>之后</b>）调用它，于是"刚打开、什么都没动"= 不脏。
     */
    public void baseline(AppConfig snapshot) {
        baseline = EffectiveParamsService.signatureOf(snapshot);
    }

    /**
     * 是否有未提交改动：当前值与基线按<b>值</b>比较不同即为脏。
     *
     * <p>纯函数（不改内部状态），故可被反复按需调用；"改回原值 → 变回不脏"也由按值比较自然成立。</p>
     */
    public boolean isDirty(AppConfig current) {
        return !baseline.equals(EffectiveParamsService.signatureOf(current));
    }

    /**
     * 提交：把当前值变成新基线（脏标记由此清零）。R1.9 只认「确认」提交，
     * 故只有参数面板的确认路径应调用它；取消 / 折叠 / 切目标都不该调。
     */
    public void commit(AppConfig current) {
        baseline(current);
    }
}
