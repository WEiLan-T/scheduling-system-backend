import { Divider, Grid, H1, H2, Stack, Stat, Table, Text } from 'qoder/canvas';

export default function GlobalOptimizationRefactoringReport() {
  return (
    <Stack gap={24}>
      <H1>排产系统全局优化重构 — 完成报告</H1>
      <Text tone="secondary">2026-08-10 | 计划全部 8 项需求实施完毕，10 个编排任务闭环，E2E 八项走查全部通过</Text>

      <Grid columns={4} gap={16}>
        <Stat value="8/8" label="需求项交付" tone="success" />
        <Stat value="8/8" label="E2E走查通过" tone="success" />
        <Stat value="2" label="新增端点（约束内）" />
        <Stat value="4" label="E2E中发现并修复的缺陷" tone="warning" />
      </Grid>

      <Divider />

      <H2>成果摘要</H2>
      <Table
        headers={['需求', '交付内容', '状态']}
        rows={[
          ['1 织造台账库', '实体补 6 个米重/耗用字段；导入/导出扩为 23 列；Python/Java 双清洗通道同步扩展 col0..col22', '完成'],
          ['2 工艺库', '实体扩为 14 业务字段；导入按「全流水线工艺BOM」14 列全映射，共挤生产速度×24 换算；导出/编辑对话框 14 列；CRUD 复用现有端点', '完成'],
          ['3 订单/询单对齐', 'ProductionOrder 补 material；修复“未入库完成米数”表头匹配 Bug；导出 15 列与模板逐字对齐', '完成'],
          ['4 产能算法', '新建 CapacityProvider；删除两引擎全部历史均值逻辑（含×2双班系数）与均值 Repo 查询；优先级=人工覆盖>工艺库>MISSING_CAPACITY', '完成'],
          ['5 虚拟库存', '导入支持机台列+动态月份列（一行多月份拆月末快照）；新建 InventoryCalculationService 日推算 + GET /inventory/daily-summary；实时联动改写当日快照', '完成'],
          ['6 带坯分切', '新增 POST /inventory/split：总长校验、XXX-1/-2 原子生成、原行核销、单事务回滚', '完成'],
          ['7 整根消耗', '新建 TapeStockConsumer 修复查询键错配 Bug；FIFO 整根贪心直接进共挤、织造只补缺口、超额明示；consumedTapeCodes 排产/询单对称生效', '完成'],
          ['8 前端同步', '工艺 14 列、织造 6 新列、订单材质、库存机台列/分切弹窗/日库存统计、MISSING_CAPACITY 回写开关、消耗清单展示', '完成'],
        ]}
      />

      <Divider />

      <H2>关键实施步骤</H2>
      <Table
        headers={['阶段', '内容', '执行方']}
        rows={[
          ['阶段0 实体重构', '4 实体扩字段 + 删兼容 getter/均值查询，作为全部后续改动前置', 'Lee'],
          ['阶段1 并行重构', '工艺库导入导出 / 织造台账 23 列 / 订单对齐 / 虚拟库存重构四线并行', 'Taylor / Felix / Jay / Robin'],
          ['阶段2 产能切换', 'CapacityProvider 组件化，MISSING_CAPACITY 结构化携带缺失字段名', 'Bill'],
          ['阶段3 分切', 'splitTape 事务方法 + /inventory/split 端点', 'Jimmy'],
          ['阶段4 整根消耗', 'QA 复验发现首轮虚假报告后重派实现，二轮逐项复验通过', 'James / Chris(QA)'],
          ['阶段5 前端', '5 个页面同步改造，全部对接既有端点', 'Taylor'],
          ['阶段6 集成验证', '编译+启动+浏览器E2E八项走查+缺陷修复回环+清理收尾', 'Nick / Chloe / Emily / David'],
        ]}
      />

      <Divider />

      <H2>变更文件清单（主要）</H2>
      <Grid columns={2} gap={16}>
        <Stack gap={8}>
          <Text tone="secondary" size="small">新增</Text>
          <Text size="small">service/scheduling/CapacityProvider.java</Text>
          <Text size="small">service/scheduling/TapeStockConsumer.java</Text>
          <Text size="small">service/InventoryCalculationService.java</Text>
          <Text size="small">dto/InventoryDailySummaryDTO.java</Text>
          <Text size="small">dto/TapeSplitRequest.java</Text>
        </Stack>
        <Stack gap={8}>
          <Text tone="secondary" size="small">修改</Text>
          <Text size="small">domain：ProductProcess / WeavingDailyLog / ProductionOrder / VirtualWarehouse</Text>
          <Text size="small">service：DataEntryService / OrderService / WeavingImportService / InventoryImportService / DataExportService / JavaDataCleaner</Text>
          <Text size="small">scheduling：SchedulingEngine / InquiryCalculator / ScheduleCommitter</Text>
          <Text size="small">repository：VirtualWarehouseRepo / WeavingDailyLogRepo / CoexDailyLogRepo / ProductProcessRepo</Text>
          <Text size="small">controller：DataEntryController（仅 +2 端点）</Text>
          <Text size="small">前端：static/index.html + static/app.js；scripts/data_cleaner.py</Text>
        </Stack>
      </Grid>

      <Divider />

      <H2>验证证据</H2>
      <Table
        headers={['验证项', '结果', '方式']}
        rows={[
          ['编译门禁', '通过', 'mvn compile BUILD SUCCESS（多轮 + 最终审计复验）'],
          ['整根消耗逻辑', '通过', 'QA 两轮逐项代码复验（查询键/machineNo过滤/FIFO/超额/consumedTapeCodes）'],
          ['工艺导入', '通过', 'E2E：543 条 14 列导入，R02501 共挤日产=速度×24=2294.16 核对正确'],
          ['织造导入', '通过', 'E2E：17255 行 23 列落库（16899 条含新列值）'],
          ['订单闭环', '通过', 'E2E：手工下单材质落库 + 导出 15 列含材质'],
          ['库存导入', '通过', 'E2E：120 条，机台列标识 + 动态月份快照（2026-07-31）'],
          ['产能链路', '通过', 'E2E：产能取工艺库值；MISSING_CAPACITY 精确报缺失字段并回写工艺库成功'],
          ['分切', '通过', 'E2E：260103→-1/-2 生成、超长前端拦截、原行核销'],
          ['整根消耗', '通过', 'E2E：consumedTapeCodes=[232204:900,250707:1140]，织造仅补缺口 2960m，询单同步生效'],
          ['最终审计', '通过', '8 项交付物 + 2 端点约束 + 清理状态逐条取证，0 不符合项'],
        ]}
      />

      <Divider />

      <H2>E2E 中发现并修复的额外缺陷</H2>
      <Table
        headers={['缺陷', '根因', '修复']}
        rows={[
          ['工艺导入重复键 14-40', '列匹配 contains("号") 被“成品规格型号”抢占零件号列', '收紧匹配链 + 防覆盖守卫'],
          ['daily-summary 时间戳越界', '无月度锚点时 LocalDate.MIN 绑定 JDBC', '安全下界 2000-01-01 + 参数校验'],
          ['排产预览 NPE', 'buildDraftView 共挤日期缺 null 保护', '双引擎对称加固'],
          ['订单保存控制台报错', 'app.js 残留未定义变量 simDialogVisible 引用', '删除死代码'],
        ]}
      />

      <Divider />

      <H2>最终状态与遗留观察项</H2>
      <Table
        headers={['事项', '说明']}
        rows={[
          ['交付状态', '临时服务已停止（8080 空闲）、临时产物已清理、最终编译门禁通过；ddl-auto=create 下次启动自动重建空库'],
          ['分切子根消耗策略', '整根消耗仅认 splitSeq 为空的整根，分切子根对排产不可见，建议与业务确认'],
          ['consumedTapeCodes 持久化', '目前随落库回执透传，未入排产表，事后追溯需另行落表'],
          ['织造列表分页', '/weaving/logs/list 全量返回 12MB，建议后续补分页'],
          ['IDE 干扰', 'IntelliJ 会自动拉起应用实例造成端口冲突，本地联调请先停止 IDE 运行配置'],
        ]}
        rowTone={[undefined, 'warning', 'warning', 'warning', undefined]}
      />

      <Divider />
      <Text tone="secondary" size="small">排产系统全局优化重构 | 生成时间: 2026-08-11</Text>
    </Stack>
  );
}
