/**
 * UI 端口层（《UI可切换重构方案》P3 起建立）：框架无关的端口契约 —— 命令 / 查询 / 事件 / 确认往返。
 *
 * <p><b>定位</b>：<b>后端实现本包、前端只依赖本包</b> —— 命令生效、查询出快照、事件推送、
 * 确认往返都由后端实现；前端只与本包的接口和视图模型 record 打交道，换 UI 框架时前端整体可替换，
 * 端口契约不变。</p>
 *
 * <p><b>本包三条红线（后端实现必须满足）</b>：</p>
 * <ol>
 *   <li><b>零 JavaFX 依赖</b>：字节码常量池不得出现任何 JavaFX 描述符
 *       （由不变式 {@code uiportHasNoJavaFxDependency} 扫描强制）；</li>
 *   <li><b>零国际化工具类依赖</b>：文案只传「键 + 具名参数」（方案 §14 决策 13），
 *       渲染属前端；不得引用任何国际化 API 或工具类；</li>
 *   <li><b>不引用任何业务实现类型</b>：不得 import
 *       {@code com.mhtextractor.{core,extractor,processor,output,cache,platform,util,config,i18n,app,model,gui,uicore}}
 *       的类；视图模型全部由本包自持 record。</li>
 * </ol>
 *
 * <p><b>当前切片（P3-2 最小切片）</b>：工作区查询（{@link WorkspaceVm}）+
 * 撤销 / 清场 / 勾选 / 取消命令（{@link WorkspaceCommands}）+ 变更事件流（{@link PortEvents}）+
 * 确认往返接缝（{@link Confirmer}）；其余端口能力（导入编排、提取编排、参数 schema、
 * 输出单元规划、预览等）留后续批次，见《端口能力覆盖矩阵》§4。</p>
 *
 * <p><b>视图模型纪律</b>：所有 record 的集合字段做<b>防御性不可变拷贝</b>
 * （{@code List.copyOf} / {@code Map.copyOf}），快照一次性成立后不再受构造方后续修改影响。</p>
 */
package com.mhtextractor.uiport;
