import { Divider, Grid, H1, H2, Stack, Stat, Table, Text } from 'qoder/canvas';

export default function AlgorithmAuditReport() {
  return (
    <Stack gap={20}>
      <H1>排产/询单算法审计与鲁棒性加固</H1>
      <Text tone="secondary" size="small">2026-08-19 | 7 files modified | 0 new files</Text>

      <Grid columns={4} gap={16}>
        <Stat value="3" label="Bug Fixes" tone="danger" />
        <Stat value="4" label="Robustness Guards" tone="warning" />
        <Stat value="7" label="Files Changed" />
        <Stat value="9/9" label="Tasks Complete" tone="success" />
      </Grid>

      <Divider />

      <H2>核心修复</H2>
      <Table
        headers={['问题', '位置', '修复方案']}
        rows={[
          ['coexBaseStart 零缺口延迟', 'SchedulingEngine L234/L545, InquiryCalculator L195', 'shortfall=0 时 coexBaseStart=now，跳过 reserveAdvanceDays'],
          ['产能验证字段缺失', 'buildDraftView (双引擎)', '增补 plannedWeavingDays/plannedCoexDays/utilization 四字段'],
          ['除零风险', 'InquiryCalculator L114, CapacityProvider', '显式 guard + assertPositive() 断言'],
        ]}
        rowTone={['danger', 'warning', 'warning']}
      />

      <Divider />

      <H2>鲁棒性加固</H2>
      <Table
        headers={['加固项', '文件', '行为']}
        rows={[
          ['日期合理性校验', 'ScheduleCommitter', 'weavingEnd≥weavingStart / coexEnd≥coexStart，warn 日志不阻断落库'],
          ['NPE 专项处理', 'GlobalExceptionHandler', 'HTTP 500 + 明确诊断消息'],
          ['applyManualAdjustments 错误分支', 'app.js', 'MISSING_CAPACITY→弹窗 / MISSING_PROCESS→跳转工艺页'],
          ['产能验证列', 'index.html', '织造/共挤/询单 3 处调度表新增 Xm/Y天(Z%) 验证列'],
        ]}
      />

      <Divider />

      <H2>变更文件清单</H2>
      <Table
        headers={['文件', '变更类型', '说明']}
        rows={[
          ['SchedulingEngine.java', 'Modified', 'coexBaseStart fix ×2 + buildDraftView fields + calcUtilization()'],
          ['InquiryCalculator.java', 'Modified', 'coexStartRef fix + buildDraftView + 除零 guard'],
          ['CapacityProvider.java', 'Modified', 'assertPositive() 断言 + 调用'],
          ['ScheduleCommitter.java', 'Modified', 'SLF4J Logger + 日期校验'],
          ['GlobalExceptionHandler.java', 'Modified', 'NullPointerException handler'],
          ['index.html', 'Modified', 'Tooltip 增强 ×2 + 产能验证列 ×3'],
          ['app.js', 'Modified', 'MISSING_CAPACITY/PROCESS 分支处理'],
        ]}
      />

      <Divider />

      <H2>E2E 验证证据</H2>
      <Grid columns={2} gap={16}>
        <Stat value="PASS" label="mvn compile" tone="success" />
        <Stat value="6.15s" label="应用启动" />
        <Stat value="95%" label="织造产能利用率" />
        <Stat value="99%" label="共挤产能利用率" />
      </Grid>
      <Text tone="secondary" size="small">
        甘特图 tooltip 显示：日产能基数 160 米/天 | 需生产 3200m（计划 21 天）| 产能利用率 95%
      </Text>
      <Text tone="secondary" size="small">
        浏览器 Console 检查：无 JS 错误
      </Text>
    </Stack>
  );
}
