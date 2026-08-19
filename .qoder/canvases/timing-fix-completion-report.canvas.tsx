import { Divider, Grid, H1, H2, Stack, Stat, Table, Text } from 'qoder/canvas';

export default function TimingFixCompletionReport() {
  return (
    <Stack gap={24}>
      <H1>排产算法时序修复 — 完成报告</H1>
      <Text tone="secondary">2026-08-17 | 4 项计划任务全部闭环，编译通过 + E2E 六项验证全部通过</Text>

      <Grid columns={4} gap={16}>
        <Stat value="4/4" label="计划任务交付" tone="success" />
        <Stat value="6/6" label="E2E 验证通过" tone="success" />
        <Stat value="3" label="变更文件" />
        <Stat value="0" label="新增接口（约束内）" />
      </Grid>

      <Divider />

      <H2>修复概述</H2>
      <Text>
        修复排产引擎时序算法缺陷：共挤开机时点从独立倒推改为基于织造结果推导，确保储备就绪即开机、共挤连续不停机（停机不早于织造）。同时加固前端机台/产线数调控链路，解决 truthy 过滤丢失和多行调整丢失问题。
      </Text>

      <Divider />

      <H2>变更文件清单</H2>
      <Table
        headers={['文件', '变更类型', '说明']}
        rows={[
          ['SchedulingEngine.java', '修改', 'previewSingleOrder + previewMultiOrder：共挤时点基于织造结果推导（coexBaseStart/coexEnd/coexStart 三级约束）'],
          ['InquiryCalculator.java', '修改', 'calculateInquiry：coexStartRef = weavingStartRef + reserveAdvanceDays；增加 coexEndRef >= weavingEndRef 安全保障'],
          ['app.js', '修改', 'applyManualAdjustments：filter 条件改 null check；Map 合并同成品多行；assignedMachineIds 收集全部机台'],
        ]}
      />

      <Divider />

      <H2>关键修复逻辑</H2>
      <Grid columns={2} gap={16}>
        <Stack gap={8}>
          <Text tone="secondary" size="small">共挤时序修复（SchedulingEngine 双链路）</Text>
          <Text size="small">1. 织造循环中收集 earliestWeavingStart / latestWeavingEnd</Text>
          <Text size="small">2. coexBaseStart = earliestWeavingStart + reserveAdvanceDays（储备就绪即开机）</Text>
          <Text size="small">3. coexEnd = max(deadline, latestWeavingEnd)（共挤连续不停机）</Text>
          <Text size="small">4. coexStart = coexEnd - coexDuration，但不早于 coexBaseStart</Text>
          <Text size="small">5. 保留资源冲突后移 + now-shift 原有逻辑不变</Text>
        </Stack>
        <Stack gap={8}>
          <Text tone="secondary" size="small">前端调控加固（app.js）</Text>
          <Text size="small">1. filter 从 truthy 改为 != null（值 0 不再被误过滤）</Text>
          <Text size="small">2. Map 按 finishedPartNumber 合并同成品多行调整为一个 adjustment</Text>
          <Text size="small">3. 织造行提供织造参数，共挤行提供共挤参数</Text>
          <Text size="small">4. assignedMachineIds 收集所有指派机台（而非仅第一个）</Text>
          <Text size="small">5. InquiryCalculator: coexStartRef 对齐储备就绪时点</Text>
        </Stack>
      </Grid>

      <Divider />

      <H2>E2E 验证结果（weavingReserveDays=8，订单 CS001）</H2>
      <Table
        headers={['验证项', '结果', '证据']}
        rows={[
          ['储备米数', '通过', '2538m = 共挤日产 317.28m × 8 天'],
          ['提前开工天数', '通过', '16 天（织造 08-17 开工 → 共挤 09-02 开工）'],
          ['共挤停机晚于织造', '通过', '共挤结束 09-28 >= 织造结束 09-27'],
          ['机台数调整实时重算', '通过', '4→2 台：每台 4000m→8000m，甘特图明显变化'],
          ['产线数调整实时重算', '通过', '2→1 条：单线 8000m→16000m，甘特条宽度 34%→69%'],
          ['Console 无 JS 错误', '通过', '所有正式 API 调用返回 200，无 TypeError/ReferenceError'],
        ]}
        rowTone={['success', 'success', 'success', 'success', 'success', 'success']}
      />

      <Divider />

      <H2>修复前后对比</H2>
      <Table
        headers={['指标', '修复前', '修复后']}
        rows={[
          ['共挤开机时点', 'coexEnd = deadline 倒推，与织造无关', 'coexBaseStart = earliestWeavingStart + reserveAdvanceDays'],
          ['共挤停机时点', 'coexEnd = deadline（可能早于织造结束）', 'coexEnd = max(deadline, latestWeavingEnd)'],
          ['储备天数作用', '仅偏移织造起点，共挤不受影响', '驱动共挤开机时点，储备就绪即开机'],
          ['多行调整合并', '每行独立 adjustment，findFirst 丢失参数', 'Map 合并同成品，后端取到完整参数'],
          ['filter 条件', 'truthy 判断（0/null/undefined 均被过滤）', 'null check（仅 null/undefined 被过滤）'],
          ['assignedMachineIds', '仅取第一个 plannedMachine', '收集所有指派机台 ID'],
        ]}
      />

      <Divider />

      <H2>验证截图索引</H2>
      <Text size="small">以下截图保存在项目根目录，可通过文件浏览器查看：</Text>
      <Table
        headers={['截图文件', '验证内容']}
        rows={[
          ['e2e_04_gantt_and_tables.png', '排产结果甘特图：储备天数=8，共挤开工与织造储备就绪对齐'],
          ['e2e_step1_baseline_gantt.png', '基准状态甘特图（4 台织造机 + 2 条共挤产线）'],
          ['e2e_step2_weaving_machine_count_2.png', '织造机台数改为 2 后甘特图实时重算'],
          ['e2e_step3_coex_line_count_1.png', '共挤产线数改为 1 后甘特图实时重算'],
          ['e2e_step4_console_with_devtools.png', 'DevTools Console 无 JS 错误'],
        ]}
      />

      <Divider />
      <Text tone="secondary" size="small">排产算法时序修复 | 生成时间: 2026-08-17</Text>
    </Stack>
  );
}
