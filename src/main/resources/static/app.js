const { createApp, ref, reactive, onMounted, watch, computed } = Vue;
const { ElMessage, ElMessageBox } = ElementPlus;

const app = createApp({
    setup() {
        const executionCurrentTime = computed(() => {
            let maxTime = 0;
            const parseDateEnd = (dStr) => {
                if (!dStr) return 0;
                return new Date(dStr + 'T23:59:59').getTime();
            };
            weavingLogList.value.forEach(log => {
                const t = parseDateEnd(log.entryDate);
                if (t > maxTime) maxTime = t;
            });
            coexLogList.value.forEach(log => {
                const t = parseDateEnd(log.logDate);
                if (t > maxTime) maxTime = t;
            });
            return maxTime > 0 ? maxTime : Date.now();
        });

        onMounted(() => {
            const token = localStorage.getItem('jwt_token');
            const user = localStorage.getItem('current_user');
            if (token && user) {
                isLoggedIn.value = true; currentUser.value = user;
                loadMachinesAndLines();
                loadOrders();  // 只加载订单和机台，其他页面切换时按需加载
            }
        });

        const getToday = () => {
            const d = new Date();
            return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        };

        // 统一提取后端错误信息（兼容 message / error 两种响应体）
        const errMsg = (e) => {
            if (e && e.response && e.response.data) {
                const d = e.response.data;
                const msg = typeof d === 'string' ? d : (d.message || d.error);
                if (msg) return msg;
            }
            return (e && e.message) || '操作失败';
        };

        // 🌟 通用表头筛选选项构建：去重 + 排序 + 转 {text, value} 数组
        const distinctFilterOptions = (values) => Array.from(new Set((values || []).filter(v => v != null && String(v).trim() !== ''))).sort().map(v => ({ text: String(v), value: v }));

        // 🌟 通用客户端列筛选方法（供所有表复用）
        const clientColumnFilter = (value, row, column) => {
            const prop = column.property;
            const cellValue = row[prop];
            if (cellValue == null) return false;
            return String(cellValue) === String(value);
        };
        // 🌟 为某列动态生成 filter options（从当前数据源提取 distinct 值）
        const colFilterOpts = (dataList, prop) => {
            const list = dataList.value || dataList;
            const seen = new Set();
            (Array.isArray(list) ? list : []).forEach(row => {
                const v = row[prop];
                if (v != null && String(v).trim() !== '') seen.add(String(v));
            });
            return Array.from(seen).sort().map(v => ({ text: v, value: v }));
        };

        const isLoggedIn = ref(false);
        const currentUser = ref('');
        const activeMenu = ref('order-dashboard');
        const loading = ref(false);

        const machineList = ref([]);
        const lineList = ref([]);

        // ===== Excel 导入结果与库存核对相关状态 =====
        const importResult = ref(null);          // 最近一次导入的 ImportResult
        const lastImportSource = ref('');        // 导入来源: weaving / coex / inventory
        const importLoading = ref(false);
        const coexImportYear = ref(null);        // 共挤导入时从文件名提取的年份
        const inventorySnapshotDate = ref(null);
        const reconciliationData = ref([]);
        const reconciliationLoading = ref(false);

        // ================= AXIOS 拦截器 =================
        axios.interceptors.request.use(config => {
            const token = localStorage.getItem('jwt_token');
            if (token) config.headers['Authorization'] = 'Bearer ' + token;
            return config;
        });

        axios.interceptors.response.use(response => response, error => {
            if (error.response && error.response.status === 403) ElMessage({ message: '⛔ 权限不足！', type: 'error' });
            else if (error.response && error.response.status === 401) { ElMessage.warning('凭证过期，请重新登入。'); handleLogout(); }
            else if (!error.config?.skipErrorHandler && error.response && error.response.data) {
                // 🌟 通用错误提示（排产接口通过 skipErrorHandler 跳过，由其自行处理）
                const errorMsg = typeof error.response.data === 'string' ? error.response.data : error.response.data.message;
                if (errorMsg) ElMessage.error(errorMsg);
            }
            return Promise.reject(error);
        });

        const loadMachinesAndLines = async () => {
            try {
                const res1 = await axios.get('/api/v1/workshops/integration/weaving/machines');
                machineList.value = res1.data;
                const res2 = await axios.get('/api/v1/workshops/integration/coextrusion/lines');
                lineList.value = res2.data;
            } catch (e) { ElMessage.error(errMsg(e)); }
        };

        const loginForm = reactive({ username: '', password: '' });
        const handleLogin = async () => {
            if (!loginForm.username || !loginForm.password) return;
            loading.value = true;
            try {
                const res = await axios.post('/api/v1/auth/login', loginForm);
                if (res.data && res.data.includes('eyJ')) {
                    localStorage.setItem('jwt_token', res.data.trim()); localStorage.setItem('current_user', loginForm.username);
                    isLoggedIn.value = true; currentUser.value = loginForm.username; ElMessage.success('核验通过！');
                    loadMachinesAndLines(); loadOrders();
                }
            } catch (error) { if(error.response && error.response.status === 401) ElMessage.error('账号或密码错误！'); else ElMessage.error(errMsg(error)); } finally { loading.value = false; }
        };

        const handleLogout = () => { localStorage.clear(); isLoggedIn.value = false; };
        const handleMenuSelect = (index) => { activeMenu.value = index; estResult.value = null; };

        const refreshCurrentPage = () => {
            if (activeMenu.value === 'dashboard') { loadMachinesAndLines(); loadWeavingLogs(); loadCoexLogs(); loadInventory(); ElMessage.success('🔄 厂区数字孪生快照已更新'); }
            else if (activeMenu.value === 'weaving') { loadWeavingPage(); ElMessage.success('🔄 织造历史台账同步刷新完成'); }
            else if (activeMenu.value === 'process') { loadProcesses(); loadProcessPage(); ElMessage.success('🔄 工艺路线参数配置库已同步刷新'); }
            else if (activeMenu.value === 'machine-archive') { loadMachinesAndLines(); ElMessage.success('🔄 织造机台档案已刷新'); }
            else if (activeMenu.value === 'line-archive') { loadMachinesAndLines(); ElMessage.success('🔄 共挤产线档案已刷新'); }
            else if (activeMenu.value === 'coex') { loadCoexPage(); ElMessage.success('🔄 共挤历史台账同步刷新完成'); }
            else if (activeMenu.value === 'inventory') { loadInventory(); loadReconciliationReport(); ElMessage.success('🔄 虚拟分批库存大盘已刷新'); }
            else if (activeMenu.value === 'order') { loadOrders(); ElMessage.success('🔄 销售合同档案订单库已刷新'); }
            else if (activeMenu.value === 'order-dashboard') { loadOrders(); ElMessage.success('🔄 订单交期全景大盘已更新'); }
            else if (activeMenu.value === 'execution') {
                loadWeavingLogs(); loadCoexLogs(); loadOrders(); loadAllSchedulePlans();
                ElMessage.success('🔄 全厂订单执行仪表板实时数据获取成功！');
            }
            else if (activeMenu.value === 'estimation') {
                if (estForm.orderId) { fetchInitialDraft(); } else { ElMessage.success('🔄 排产控制中心已就绪'); }
            }
            else if (activeMenu.value === 'inquiry') {
                ElMessage.success('🔄 询单预估页面已刷新');
            }
        };

        // ==========================================
        // 🧶 织造车间 MES
        // ==========================================
        const weavingLogList = ref([]); const weavingFileRef = ref(null);
        // 🌟 服务端分页 + 表头筛选状态（契约：keyword 模糊匹配 partNumber/tapeCode/modelSpec，筛选列 machineNo/shiftType）
        const weavingKeyword = ref('');
        const weavingFilters = reactive({ machineNo: null, shiftType: null });
        const weavingPage = ref(1); const weavingPageSize = ref(10); const weavingTotal = ref(0);
        const paginatedWeavingLogs = ref([]);
        // 表头筛选选项：机台号取自机台档案，班次为静态枚举
        const weavingMachineFilterOptions = computed(() => machineList.value.map(m => ({ text: '机台 ' + m.machineId + '#', value: Number(cleanId(m.machineId)) })).filter(f => !isNaN(f.value)));
        const weavingShiftFilterOptions = [{ text: '白班', value: '白' }, { text: '夜班', value: '夜' }];

        const weavingForm = reactive({
            id: null, entryDate: getToday(), machineId: '', tapePartNumber: '', tapeNumber: '',
            modelSpec: '', warpSpec: '', weftSpec: '', shift: '白', operatorName: '',
            capacityPerDay: 0, standardCapacity: 0, standardHours: 0, standardHourlyCapacity: 0,
            performanceHours: 0, isDataNormal: true, totalDemand: 0, remarks: '', workshopId: '织造车间',
            // 🌟 新增6字段：米重/耗用（可选补录）
            warpWeightPerMeter: null, weftWeightPerMeter2000D: null, weftWeightPerMeter3000D: null,
            warpUsageKgPerMeter: null, weftUsageKgPerMeter2000D: null, weftUsageKgPerMeter3000D: null
        });

        watch(() => weavingForm.tapePartNumber, (newVal) => {
            if (newVal && processList.value.length > 0) {
                const proc = processList.value.find(p => p.tapePartNumber === newVal);
                if (proc) {
                    if (!weavingForm.modelSpec && proc.tapeModelSpec) weavingForm.modelSpec = proc.tapeModelSpec;
                    if (!weavingForm.warpSpec && proc.warpSpec) weavingForm.warpSpec = proc.warpSpec;
                    if (!weavingForm.weftSpec && proc.weftSpec) weavingForm.weftSpec = proc.weftSpec;
                    // 米重三件套同样支持从工艺库自动补齐
                    if (weavingForm.warpWeightPerMeter == null && proc.warpWeightPerMeter != null) weavingForm.warpWeightPerMeter = proc.warpWeightPerMeter;
                    if (weavingForm.weftWeightPerMeter3000D == null && proc.weftWeightPerMeter3000D != null) weavingForm.weftWeightPerMeter3000D = proc.weftWeightPerMeter3000D;
                    if (weavingForm.weftWeightPerMeter2000D == null && proc.weftWeightPerMeter2000D != null) weavingForm.weftWeightPerMeter2000D = proc.weftWeightPerMeter2000D;
                    // 织造标准产能(米/24h)从工艺库自动带出，仅赋值不锁定，用户仍可手改
                    if (proc.weavingStandardDailyOutput != null) weavingForm.standardCapacity = proc.weavingStandardDailyOutput;
                }
            }
        });

        const resetWeavingForm = () => {
            weavingForm.id = null; weavingForm.tapePartNumber = ''; weavingForm.tapeNumber = '';
            weavingForm.modelSpec = ''; weavingForm.warpSpec = ''; weavingForm.weftSpec = '';
            weavingForm.operatorName = ''; weavingForm.capacityPerDay = 0; weavingForm.standardCapacity = 0;
            weavingForm.standardHours = 0; weavingForm.standardHourlyCapacity = 0; weavingForm.performanceHours = 0;
            weavingForm.totalDemand = 0; weavingForm.isDataNormal = true; weavingForm.remarks = '';
            weavingForm.warpWeightPerMeter = null; weavingForm.weftWeightPerMeter2000D = null; weavingForm.weftWeightPerMeter3000D = null;
            weavingForm.warpUsageKgPerMeter = null; weavingForm.weftUsageKgPerMeter2000D = null; weavingForm.weftUsageKgPerMeter3000D = null;
        };

        watch(() => weavingForm.machineId, (newId) => {
            const machine = machineList.value.find(m => m.machineId === newId);
            if (machine) { weavingForm.workshopId = machine.workshopId; }
        });

        const loadWeavingLogs = async () => { try { const res = await axios.get('/api/v1/workshops/integration/weaving/logs/list', { skipErrorHandler: true }); weavingLogList.value = res.data; } catch (e) { ElMessage.error(errMsg(e)); } };
        // 🌟 织造台账列表页：服务端分页查询（page 从 0 开始，返回 {content, totalElements, totalPages}）
        const loadWeavingPage = async () => {
            try {
                const params = { page: weavingPage.value - 1, size: weavingPageSize.value };
                if (weavingKeyword.value && weavingKeyword.value.trim()) params.keyword = weavingKeyword.value.trim();
                if (weavingFilters.machineNo != null && weavingFilters.machineNo !== '') params.machineNo = weavingFilters.machineNo;
                if (weavingFilters.shiftType) params.shiftType = weavingFilters.shiftType;
                const res = await axios.get('/api/v1/workshops/integration/weaving/logs/list', { params, skipErrorHandler: true });
                const data = res.data || {};
                paginatedWeavingLogs.value = Array.isArray(data.content) ? data.content : [];
                weavingTotal.value = data.totalElements || 0;
            } catch (e) { ElMessage.error(errMsg(e)); }
        };
        const searchWeaving = () => { weavingPage.value = 1; loadWeavingPage(); };
        const handleWeavingFilterChange = ({ property, filters }) => {
            if (property === 'machineNo' || property === 'shiftType') {
                weavingFilters[property] = (filters && filters.length > 0) ? filters[0] : null;
                weavingPage.value = 1; loadWeavingPage();
            }
            // 其他列为客户端筛选，无需服务端重载
        };
        const onWeavingSizeChange = () => { weavingPage.value = 1; loadWeavingPage(); };
        const onWeavingPageChange = () => { loadWeavingPage(); };
        const submitWeaving = async () => { loading.value = true; try { const res = await axios.post('/api/v1/workshops/integration/weaving/logs', weavingForm, { skipErrorHandler: true }); ElMessage.success(res.data); loadWeavingPage(); resetWeavingForm(); } catch (error) { ElMessage.error(errMsg(error)); } finally { loading.value = false; } };
        const openEditWeaving = (row) => {
            // 台账实体新字段名 → 手工录入表单字段名映射（后端DTO仍使用旧字段名）
            Object.assign(weavingForm, row, {
                tapePartNumber: row.partNumber ?? '', tapeNumber: row.tapeCode ?? '',
                machineId: row.machineNo ?? '', warpSpec: row.warpThread ?? '', weftSpec: row.weftThread ?? '',
                shift: row.shiftType ?? '白', operatorName: row.workerName ?? '',
                capacityPerDay: row.shiftOutput ?? 0, standardHourlyCapacity: row.standardHourCapacity ?? 0,
                remarks: row.remark ?? ''
            });
            window.scrollTo({ top: 0, behavior: 'smooth' });
        };
        const deleteWeaving = async (id) => { try { await ElMessageBox.confirm('撤销台账将自动回扣并同步冲减库存，是否继续？', '高危生产警告', { type: 'warning' }); const res = await axios.delete(`/api/v1/workshops/integration/weaving/logs/${id}`, { skipErrorHandler: true }); ElMessage.success(res.data); loadWeavingPage(); if (weavingForm.id === id) resetWeavingForm(); } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(errMsg(e)); } };

        const exportWeavingExcel = async () => { try { const res = await axios.get('/api/v1/workshops/integration/weaving/export', { responseType: 'blob' }); const blob = new Blob([res.data]); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '织造车间产能明细汇总.xlsx'; link.click(); ElMessage.success('📥 导出成功！'); } catch(e) { ElMessage.error('导出失败'); } };
        const handleWeavingImport = async (e) => {
            const file = e.target.files[0]; if (!file) return;
            const fd = new FormData(); fd.append('file', file);
            loading.value = true; importResult.value = null;
            try {
                const res = await axios.post('/api/v1/workshops/integration/weaving/import', fd, { headers: { 'Content-Type': 'multipart/form-data' }, skipErrorHandler: true });
                importResult.value = res.data; lastImportSource.value = 'weaving';
                ElMessage.success(res.data.message || '导入完成');
                loadWeavingPage();
            } catch (err) { ElMessage.error(errMsg(err)); } finally { loading.value = false; e.target.value = ''; }
        };

        // 🔍 触发织造B级数据重检
        const recheckGradeB = async () => {
            try {
                const res = await axios.post('/api/v1/workshops/integration/data-quality/recheck', null, { skipErrorHandler: true });
                const weaving = (res.data && res.data.weaving) || {};
                ElMessage.success(`B级数据重检完成：重检 ${weaving.totalGradeB ?? 0} 条，升级A级 ${weaving.upgradedToA ?? 0} 条`);
                loadWeavingPage();
            } catch (e) { ElMessage.error(errMsg(e)); }
        };

        // ==========================================
        // 🗜️ 共挤车间 MES
        // ==========================================
        const coexLogList = ref([]); const coexFileRef = ref(null);
        // 🌟 服务端分页 + 表头筛选状态（契约：keyword 模糊匹配 machineNo/productModel/productType，筛选列 machineNo/productModel/color）
        const coexKeyword = ref('');
        const coexFilters = reactive({ machineNo: null, productModel: null, color: null });
        const coexPage = ref(1); const coexPageSize = ref(10); const coexTotal = ref(0);
        const paginatedCoexLogs = ref([]);
        // 表头筛选选项：机台号取自产线档案，型号/颜色取自已加载台账数据
        const coexMachineFilterOptions = computed(() => lineList.value.map(l => ({ text: l.lineId + '号线', value: l.lineId })));
        const coexModelFilterOptions = computed(() => distinctFilterOptions(coexLogList.value.map(i => i.productModel)));
        const coexColorFilterOptions = computed(() => distinctFilterOptions(coexLogList.value.map(i => i.color)));

        const coexForm = reactive({
            id: null, entryDate: getToday(), lineId: '', orderNumber: '',
            finishedPartNumber: '', semiFinishedNumber: '', finishedModelSpec: '',
            workshopId: '', caliberMin: null, caliberMax: null, lineStatus: '在产', productionSpeed: 0,
            capacityPerDay: 0, isDataNormal: true, tapeDemandQty: 0, tapePartNumber: '', tapeNumber: '', remarks: ''
        });

        watch(() => coexForm.orderNumber, (newVal) => {
            if (newVal && orderList.value.length > 0) {
                const order = orderList.value.find(o => String(o.orderId) === String(newVal));
                if (order && order.items && order.items.length > 0) {
                    const item = order.items[0];
                    if (!coexForm.finishedPartNumber) coexForm.finishedPartNumber = item.finishedPartNumber;
                    if (!coexForm.finishedModelSpec) coexForm.finishedModelSpec = item.modelSpec;
                }
            }
        });
        watch(() => coexForm.finishedPartNumber, (newVal) => {
            if (newVal && processList.value.length > 0) {
                const proc = processList.value.find(p => p.finishedPartNumber === newVal);
                if (proc && !coexForm.tapePartNumber) {
                    coexForm.tapePartNumber = proc.tapePartNumber;
                }
            }
        });

        const resetCoexForm = () => {
            coexForm.id = null; coexForm.orderNumber = ''; coexForm.finishedPartNumber = ''; coexForm.semiFinishedNumber = '';
            coexForm.finishedModelSpec = ''; coexForm.tapeNumber = ''; coexForm.productionSpeed = 0; coexForm.capacityPerDay = 0;
            coexForm.tapeDemandQty = 0; coexForm.isDataNormal = true; coexForm.remarks = '';
        };

        watch(() => coexForm.lineId, (newId) => {
            const line = lineList.value.find(l => l.lineId === newId);
            if (line) { coexForm.workshopId = line.workshopId; coexForm.caliberMin = line.caliberMin; coexForm.caliberMax = line.caliberMax; }
        });

        const loadCoexLogs = async () => { try { const res = await axios.get('/api/v1/workshops/integration/coextrusion/logs/list', { skipErrorHandler: true }); coexLogList.value = res.data; } catch (e) { ElMessage.error(errMsg(e)); } };
        // 🌟 共挤台账列表页：服务端分页查询
        const loadCoexPage = async () => {
            try {
                const params = { page: coexPage.value - 1, size: coexPageSize.value };
                if (coexKeyword.value && coexKeyword.value.trim()) params.keyword = coexKeyword.value.trim();
                if (coexFilters.machineNo != null && coexFilters.machineNo !== '') params.machineNo = coexFilters.machineNo;
                if (coexFilters.productModel) params.productModel = coexFilters.productModel;
                if (coexFilters.color) params.color = coexFilters.color;
                const res = await axios.get('/api/v1/workshops/integration/coextrusion/logs/list', { params, skipErrorHandler: true });
                const data = res.data || {};
                paginatedCoexLogs.value = Array.isArray(data.content) ? data.content : [];
                coexTotal.value = data.totalElements || 0;
            } catch (e) { ElMessage.error(errMsg(e)); }
        };
        const searchCoex = () => { coexPage.value = 1; loadCoexPage(); };
        const handleCoexFilterChange = ({ property, filters }) => {
            if (property === 'machineNo' || property === 'productModel' || property === 'color') {
                coexFilters[property] = (filters && filters.length > 0) ? filters[0] : null;
                coexPage.value = 1; loadCoexPage();
            }
            // 其他列为客户端筛选，无需服务端重载
        };
        const onCoexSizeChange = () => { coexPage.value = 1; loadCoexPage(); };
        const onCoexPageChange = () => { loadCoexPage(); };
        const submitCoex = async () => { loading.value = true; try { const res = await axios.post('/api/v1/workshops/integration/coextrusion/logs', coexForm, { skipErrorHandler: true }); ElMessage.success(res.data); loadCoexPage(); resetCoexForm(); } catch (error) { ElMessage.error(errMsg(error)); } finally { loading.value = false; } };
        const openEditCoex = (row) => {
            // 台账实体新字段名 → 手工录入表单字段名映射（后端DTO仍使用旧字段名）
            Object.assign(coexForm, row, {
                entryDate: row.logDate ?? '', lineId: row.machineNo ?? '',
                finishedModelSpec: row.productModel ?? '', capacityPerDay: row.capacityMeters ?? 0,
                tapeDemandQty: row.capacityMeters ?? 0, remarks: ''
            });
            window.scrollTo({ top: 0, behavior: 'smooth' });
        };
        const deleteCoex = async (id) => { try { await ElMessageBox.confirm('确认删除并退还库存？', '警告', { type: 'warning' }); const res = await axios.delete(`/api/v1/workshops/integration/coextrusion/logs/${id}`, { skipErrorHandler: true }); ElMessage.success(res.data); loadCoexPage(); if (coexForm.id === id) resetCoexForm(); } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(errMsg(e)); } };

        const exportCoexExcel = async () => { try { const res = await axios.get('/api/v1/workshops/integration/coextrusion/export', { responseType: 'blob' }); const blob = new Blob([res.data]); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '共挤车间历史台账汇总.xlsx'; link.click(); ElMessage.success('📥 导出成功！'); } catch(e) { ElMessage.error('导出失败'); } };
        const handleCoexImport = async (e) => {
            const file = e.target.files[0]; if (!file) return;
            const fd = new FormData(); fd.append('file', file);
            loading.value = true; importResult.value = null; coexImportYear.value = null;
            try {
                const res = await axios.post('/api/v1/workshops/integration/coextrusion/import', fd, { headers: { 'Content-Type': 'multipart/form-data' }, skipErrorHandler: true });
                importResult.value = res.data; lastImportSource.value = 'coex';
                // 从文件名提取年份（与后端 ExcelUtils.extractYearFromFileName 规则一致）
                const m = (file.name || '').match(/(20|19)\d{2}/);
                coexImportYear.value = m ? parseInt(m[0]) : null;
                ElMessage.success(res.data.message || '导入完成');
                if (coexImportYear.value) ElMessage.info('从文件名提取年份: ' + coexImportYear.value);
                loadCoexPage();
            } catch (err) { ElMessage.error(errMsg(err)); } finally { loading.value = false; e.target.value = ''; }
        };

        // ==========================================
        // 📦 虚拟库存总览
        // ==========================================
        const invSearchKeyword = ref(''); const inventoryList = ref([]); const invLoading = ref(false); const invDialogVisible = ref(false); const invSaveLoading = ref(false); const invForm = reactive({ id: null, partNumber: '', tapeCode: '', modelSpec: '', stockMeters: 0, snapshotDate: getToday(), stockType: '库存' });
        // 🌟 全量加载 + 前端分组/前端分页（CodeReview修复：服务端行级分页会把同零件号的卷记录按页边界切开，导致分组总量低估/重复分组）
        const invPage = ref(1); const invPageSize = ref(10);
        const invFilters = reactive({ stockTypes: null });
        const invStockTypeFilterOptions = [{ text: '库存', value: '库存' }, { text: '订单', value: '订单' }, { text: '滞留', value: '滞留' }];

        const loadInventory = async () => {
            invLoading.value = true;
            try {
                // 全量接口（不带 page/size 即旧全量行为，返回最新快照数组）
                const res = await axios.get('/api/v1/workshops/integration/inventory/list', { skipErrorHandler: true });
                inventoryList.value = Array.isArray(res.data) ? res.data : [];
            } catch (error) { ElMessage.error(errMsg(error)); } finally { invLoading.value = false; }
        };
        // 前端过滤（keyword 模糊匹配 partNumber/tapeCode/modelSpec，大小写不敏感；stockTypes 表头筛选精确匹配）后按 partNumber 分组
        const groupedInventory = computed(() => {
            const kw = (invSearchKeyword.value || '').trim().toLowerCase();
            const st = invFilters.stockTypes;
            const rows = inventoryList.value.filter(item => {
                if (kw && ![(item.partNumber || ''), (item.tapeCode || ''), (item.modelSpec || '')].some(v => String(v).toLowerCase().includes(kw))) return false;
                if (st && item.stockType !== st) return false;
                return true;
            });
            const groupMap = {};
            rows.forEach(item => {
                const pn = item.partNumber;
                if (!groupMap[pn]) { groupMap[pn] = { partNumber: pn, modelSpec: item.modelSpec, snapshotDate: item.snapshotDate, totalStockMeters: 0, batches: [], stockTypes: [] }; }
                groupMap[pn].totalStockMeters += Number(item.stockMeters || 0); groupMap[pn].batches.push(item);
                if (item.stockType && !groupMap[pn].stockTypes.includes(item.stockType)) groupMap[pn].stockTypes.push(item.stockType);
                if (item.snapshotDate && (!groupMap[pn].snapshotDate || item.snapshotDate > groupMap[pn].snapshotDate)) groupMap[pn].snapshotDate = item.snapshotDate;
            });
            return Object.values(groupMap);
        });
        // total = 分组后条数；前端分页切片
        const invTotal = computed(() => groupedInventory.value.length);
        const paginatedInventoryList = computed(() => {
            const list = groupedInventory.value;
            const maxPage = Math.max(1, Math.ceil(list.length / invPageSize.value));
            const p = Math.min(invPage.value, maxPage);
            const start = (p - 1) * invPageSize.value;
            return list.slice(start, start + invPageSize.value);
        });
        const searchInventory = () => { invPage.value = 1; };
        const handleInvFilterChange = ({ property, filters }) => {
            if (property === 'stockTypes') invFilters.stockTypes = (filters && filters.length > 0) ? filters[0] : null;
            invPage.value = 1;
        };
        const onInvSizeChange = () => { invPage.value = 1; };
        const onInvPageChange = () => { /* paginatedInventoryList 为 computed，自动重算 */ };
        const openAddInv = () => { Object.assign(invForm, { id: null, partNumber: '', tapeCode: '', modelSpec: '', stockMeters: 0, snapshotDate: getToday(), stockType: '库存' }); invDialogVisible.value = true; };
        const openEditInv = (row) => { Object.assign(invForm, { id: row.id, partNumber: row.partNumber, tapeCode: row.tapeCode || '', modelSpec: row.modelSpec || '', stockMeters: Number(row.stockMeters || 0), snapshotDate: row.snapshotDate || getToday(), stockType: row.stockType || '库存' }); invDialogVisible.value = true; };
        const saveInv = async () => { if (!invForm.partNumber) { ElMessage.warning('带坯零件号不能为空！'); return; } invSaveLoading.value = true; try { const res = await axios.post('/api/v1/workshops/integration/inventory/save', invForm, { skipErrorHandler: true }); ElMessage.success(res.data); invDialogVisible.value = false; loadInventory(); } catch (error) { ElMessage.error(errMsg(error)); } finally { invSaveLoading.value = false; } };
        const deleteInv = async (id) => { try { await axios.delete(`/api/v1/workshops/integration/inventory/${id}`, { skipErrorHandler: true }); loadInventory(); ElMessage.success('已删除');} catch (error) { ElMessage.error(errMsg(error)); } };

        // ===== 📥 库存Excel导入 / 导出 / 差值核对 =====
        const handleInventoryFileChange = (e) => { inventoryFile.value = e.target.files[0] || null; e.target.value = ''; };

        const importInventory = async () => {
            if (!inventoryFile.value) { ElMessage.warning('请先选择Excel文件'); return; }
            importLoading.value = true; importResult.value = null;
            try {
                const fd = new FormData(); fd.append('file', inventoryFile.value);
                let url = '/api/v1/workshops/integration/inventory/import';
                if (inventorySnapshotDate.value) url += '?snapshotDate=' + inventorySnapshotDate.value;
                const res = await axios.post(url, fd, { headers: { 'Content-Type': 'multipart/form-data' }, skipErrorHandler: true });
                importResult.value = res.data; lastImportSource.value = 'inventory';
                ElMessage.success(res.data.message || '导入成功');
                loadReconciliationReport(); // 导入后自动加载差值报表
                loadInventory();            // 刷新库存列表
            } catch (e) { ElMessage.error(errMsg(e)); } finally { importLoading.value = false; }
        };

        const exportInventory = async () => {
            try {
                let url = '/api/v1/workshops/integration/inventory/export';
                if (inventorySnapshotDate.value) url += '?snapshotDate=' + inventorySnapshotDate.value;
                const res = await axios.get(url, { responseType: 'blob', skipErrorHandler: true });
                const blob = new Blob([res.data]);
                const link = document.createElement('a');
                link.href = window.URL.createObjectURL(blob);
                link.download = 'inventory_reconciliation.xlsx';
                link.click();
                ElMessage.success('导出成功');
            } catch (e) { ElMessage.error('导出失败: ' + errMsg(e)); }
        };

        const loadReconciliationReport = async () => {
            reconciliationLoading.value = true;
            try {
                let url = '/api/v1/workshops/integration/inventory/reconciliation';
                if (inventorySnapshotDate.value) url += '?snapshotDate=' + inventorySnapshotDate.value;
                const res = await axios.get(url, { skipErrorHandler: true });
                reconciliationData.value = res.data || [];
            } catch (e) { ElMessage.error('加载差值报表失败: ' + errMsg(e)); } finally { reconciliationLoading.value = false; }
        };

        const confirmReconciliation = async (row) => {
            try {
                await axios.post('/api/v1/workshops/integration/inventory/reconciliation/confirm/' + row.id, null, { skipErrorHandler: true });
                ElMessage.success('核对确认成功');
                loadReconciliationReport();
                loadInventory();
            } catch (e) { ElMessage.error('确认失败: ' + errMsg(e)); }
        };

        // ===== ✂️ 带坯分切（POST /inventory/split）=====
        const splitDialogVisible = ref(false);
        const splitSaving = ref(false);
        const splitSource = reactive({ id: null, tapeCode: '', stockMeters: 0 });
        const splitLengths = ref([null]);
        const splitTotal = computed(() => splitLengths.value.reduce((s, v) => s + (Number(v) > 0 ? Number(v) : 0), 0));
        const openSplitInv = (row) => {
            Object.assign(splitSource, { id: row.id, tapeCode: row.tapeCode || '', stockMeters: Number(row.stockMeters || 0) });
            splitLengths.value = [null];
            splitDialogVisible.value = true;
        };
        const addSplitRow = () => { splitLengths.value.push(null); };
        const removeSplitRow = (idx) => { if (splitLengths.value.length > 1) splitLengths.value.splice(idx, 1); else ElMessage.warning('至少保留一根！'); };
        const submitSplit = async () => {
            const lengths = splitLengths.value.filter(v => v != null && Number(v) > 0).map(Number);
            if (lengths.length === 0) { ElMessage.warning('请至少填写一根有效长度！'); return; }
            const total = lengths.reduce((s, v) => s + v, 0);
            if (total > Number(splitSource.stockMeters) + 1e-9) {
                ElMessage.error(`分切总长 ${total.toFixed(2)} 米超出原卷长度 ${Number(splitSource.stockMeters).toFixed(2)} 米，请调整！`);
                return;
            }
            splitSaving.value = true;
            try {
                const res = await axios.post('/api/v1/workshops/integration/inventory/split', { id: splitSource.id, lengths: lengths }, { skipErrorHandler: true });
                ElMessage.success(res.data || '分切成功');
                splitDialogVisible.value = false;
                loadInventory();
            } catch (e) { ElMessage.error(errMsg(e)); } finally { splitSaving.value = false; }
        };

        // ===== 📈 日库存推算统计（GET /inventory/daily-summary）=====
        const dailySummaryVisible = ref(false);
        const dailySummaryLoading = ref(false);
        const dailySummaryRange = ref(null);
        const dailySummaryData = ref([]);
        const loadDailySummary = async () => {
            dailySummaryLoading.value = true;
            try {
                const params = {};
                if (dailySummaryRange.value && dailySummaryRange.value.length === 2) {
                    params.startDate = dailySummaryRange.value[0];
                    params.endDate = dailySummaryRange.value[1];
                } else {
                    params.date = getToday();
                }
                const res = await axios.get('/api/v1/workshops/integration/inventory/daily-summary', { params: params, skipErrorHandler: true });
                dailySummaryData.value = res.data || [];
            } catch (e) { ElMessage.error('日库存统计查询失败: ' + errMsg(e)); } finally { dailySummaryLoading.value = false; }
        };
        const openDailySummary = () => { dailySummaryVisible.value = true; loadDailySummary(); };

        // ==========================================
        // 📦 库存导入文件引用
        // ==========================================
        const inventoryFile = ref(null);

        // ==========================================
        // ⚙️ 工艺路线配置库
        // ==========================================
        const processList = ref([]); const processFileRef = ref(null); const processDialogVisible = ref(false);
        // ⚙️ 工艺库14业务字段默认模板
        const emptyProcessForm = () => ({
            id: null, finishedPartNumber: '', finishedModelSpec: '', materialType: '',
            coexMaxDailyOutput: null, tapePartNumber: '', tapeModelSpec: '', weavingStandardDailyOutput: null,
            warpSpec: '', weftSpec: '', weftSpec3000D: '', weftSpec2000D: '',
            warpWeightPerMeter: null, weftWeightPerMeter3000D: null, weftWeightPerMeter2000D: null, glueUsagePerMeter: null
        });
        const processForm = reactive(emptyProcessForm());
        // 🌟 服务端分页 + 表头筛选状态（契约：keyword 模糊匹配 finishedPartNumber/tapePartNumber/finishedModelSpec，筛选列 materialType）
        const processKeyword = ref('');
        const processFilters = reactive({ materialType: null });
        const processPage = ref(1); const processPageSize = ref(10); const processTotal = ref(0);
        const paginatedProcessList = ref([]);
        // 表头筛选选项：材质类型取自工艺库已加载数据
        const processMaterialFilterOptions = computed(() => distinctFilterOptions(processList.value.map(p => p.materialType)));

        const loadProcesses = async (silent) => { try { const res = await axios.get('/api/v1/workshops/integration/process/list', { skipErrorHandler: true }); processList.value = res.data; } catch (e) { if (!silent) ElMessage.error(errMsg(e)); } };
        // 🌟 工艺库列表页：服务端分页查询
        const loadProcessPage = async () => {
            try {
                const params = { page: processPage.value - 1, size: processPageSize.value };
                if (processKeyword.value && processKeyword.value.trim()) params.keyword = processKeyword.value.trim();
                if (processFilters.materialType) params.materialType = processFilters.materialType;
                const res = await axios.get('/api/v1/workshops/integration/process/list', { params, skipErrorHandler: true });
                const data = res.data || {};
                paginatedProcessList.value = Array.isArray(data.content) ? data.content : [];
                processTotal.value = data.totalElements || 0;
            } catch (e) { ElMessage.error(errMsg(e)); }
        };
        const searchProcess = () => { processPage.value = 1; loadProcessPage(); };
        const handleProcessFilterChange = ({ property, filters }) => {
            if (property === 'materialType') {
                processFilters.materialType = (filters && filters.length > 0) ? filters[0] : null;
                processPage.value = 1; loadProcessPage();
            }
            // 其他列为客户端筛选，无需服务端重载
        };
        const onProcessSizeChange = () => { processPage.value = 1; loadProcessPage(); };
        const onProcessPageChange = () => { loadProcessPage(); };
        const openAddProcess = () => { Object.assign(processForm, emptyProcessForm()); processDialogVisible.value = true; };
        const openEditProcess = (row) => { Object.assign(processForm, emptyProcessForm(), row); processDialogVisible.value = true; };
        const saveProcess = async () => { if (!processForm.finishedPartNumber || !processForm.tapePartNumber) { ElMessage.error('零件号不能为空！'); return; } try { const payload = { ...processForm, weftSpec: processForm.weftSpec3000D || processForm.weftSpec }; const res = await axios.post('/api/v1/workshops/integration/process/save', payload, { skipErrorHandler: true }); ElMessage.success(res.data); processDialogVisible.value = false; loadProcesses(true); loadProcessPage(); } catch (e) { ElMessage.error(errMsg(e)); } };
        const deleteProcess = async (id) => { try { await ElMessageBox.confirm('确认解除？', '警告', { type: 'warning' }); const res = await axios.delete(`/api/v1/workshops/integration/process/${id}`, { skipErrorHandler: true }); ElMessage.success(res.data); loadProcesses(true); loadProcessPage(); } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(errMsg(e)); } };

        const exportProcessExcel = async () => { try { const response = await axios.get('/api/v1/workshops/integration/process/export', { responseType: 'blob' }); const blob = new Blob([response.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '工艺路线数据大盘.xlsx'; link.click(); ElMessage.success('📥 导出成功！'); } catch (error) { ElMessage.error('导出失败，请检查服务器！'); } };
        const handleProcessImport = async (event) => { const file = event.target.files[0]; if (!file) return; const formData = new FormData(); formData.append('file', file); loading.value = true; try { const response = await axios.post('/api/v1/workshops/integration/process/import', formData, { headers: { 'Content-Type': 'multipart/form-data' }, skipErrorHandler: true }); ElMessage.success(response.data); loadProcesses(true); loadProcessPage(); } catch (error) { ElMessage.error(errMsg(error)); } finally { loading.value = false; event.target.value = ''; } };

        // ==========================================
        // 🧶 织造机台档案（复用 machineList 全量数据 + 前端分页）
        // ==========================================
                // 🌟 口径工具函数：格式化口径范围为展示字符串
                const caliberLabel = (item) => {
                    if (item.caliberMin == null && item.caliberMax == null) return '';
                    return (item.caliberMin ?? '?') + '-' + (item.caliberMax ?? '?');
                };

        const machineArchiveKeyword = ref('');
        const machineArchivePage = ref(1); const machineArchivePageSize = ref(10);
        const machineDialogVisible = ref(false); const machineArchiveEditMode = ref(false); const machineFileRef = ref(null);
        const emptyMachineForm = () => ({ machineId: '', caliberMin: null, caliberMax: null, workshopId: '', warpSpec: '', weftSpec: '', bobbinCount: null, machineStatus: '', adjacentMachine: '', operatorName: '' });
        const machineForm = reactive(emptyMachineForm());

        // 🌟 档案列表接口为全量返回（无服务端分页），故此处为前端关键字过滤 + computed slice 分页
        const filteredMachineArchive = computed(() => {
            const kw = machineArchiveKeyword.value.trim().toLowerCase();
            if (!kw) return machineList.value;
            return machineList.value.filter(m => [m.machineId, m.workshopId, m.warpSpec, m.weftSpec, m.machineStatus, m.adjacentMachine, m.operatorName, caliberLabel(m)].some(v => v != null && String(v).toLowerCase().includes(kw)));
        });
        const machineArchiveTotal = computed(() => filteredMachineArchive.value.length);
        const paginatedMachineArchiveList = computed(() => {
            const start = (machineArchivePage.value - 1) * machineArchivePageSize.value;
            return filteredMachineArchive.value.slice(start, start + machineArchivePageSize.value);
        });
        const searchMachineArchive = () => { machineArchivePage.value = 1; };

        const openAddMachineArchive = () => { machineArchiveEditMode.value = false; Object.assign(machineForm, emptyMachineForm()); machineDialogVisible.value = true; };
        const openEditMachineArchive = (row) => {
            machineArchiveEditMode.value = true;
                        Object.assign(machineForm, emptyMachineForm(), {
                machineId: row.machineId, workshopId: row.workshopId || '', warpSpec: row.warpSpec || '', weftSpec: row.weftSpec || '',
                bobbinCount: row.bobbinCount != null ? row.bobbinCount : null, machineStatus: row.machineStatus || '',
                adjacentMachine: row.adjacentMachine || '', operatorName: row.operatorName || '',
                caliberMin: row.caliberMin != null ? row.caliberMin : null, caliberMax: row.caliberMax != null ? row.caliberMax : null
            });
            machineDialogVisible.value = true;
        };
        const saveMachineArchive = async () => {
            if (!machineForm.machineId) { ElMessage.error('机台号不能为空！'); return; }
            const payload = {
                machineId: machineForm.machineId,
                caliberMin: machineForm.caliberMin != null ? machineForm.caliberMin : undefined,
                caliberMax: machineForm.caliberMax != null ? machineForm.caliberMax : undefined,
                workshopId: machineForm.workshopId || undefined, warpSpec: machineForm.warpSpec || undefined,
                weftSpec: machineForm.weftSpec || undefined, bobbinCount: machineForm.bobbinCount != null ? machineForm.bobbinCount : undefined,
                machineStatus: machineForm.machineStatus || undefined, adjacentMachine: machineForm.adjacentMachine || undefined,
                operatorName: machineForm.operatorName || undefined
            };
            try {
                const res = await axios.post('/api/v1/workshops/integration/weaving/machines', payload, { skipErrorHandler: true });
                ElMessage.success(typeof res.data === 'string' ? res.data : '保存成功');
                machineDialogVisible.value = false;
                loadMachinesAndLines(); // 同步刷新全景卡片与甘特图数据源（本页列表复用 machineList 自动更新）
            } catch (e) { ElMessage.error(errMsg(e)); }
        };
        const deleteMachineArchive = async (machineId) => {
            try {
                await ElMessageBox.confirm(`确认删除机台档案 [${machineId}] ？若被历史台账引用将被拒绝。`, '警告', { type: 'warning' });
                const res = await axios.delete(`/api/v1/workshops/integration/weaving/machines/${encodeURIComponent(machineId)}`, { skipErrorHandler: true });
                ElMessage.success(typeof res.data === 'string' ? res.data : '删除成功');
                loadMachinesAndLines();
            } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(errMsg(e)); }
        };
        const handleMachineArchiveImport = async (event) => {
            const file = event.target.files[0]; if (!file) return;
            const formData = new FormData(); formData.append('file', file);
            loading.value = true; importResult.value = null;
            try {
                const response = await axios.post('/api/v1/workshops/integration/weaving/machines/import', formData, { headers: { 'Content-Type': 'multipart/form-data' }, skipErrorHandler: true });
                importResult.value = response.data; lastImportSource.value = 'machine-archive';
                ElMessage.success(response.data.message || '导入完成');
                loadMachinesAndLines();
            } catch (error) { ElMessage.error(errMsg(error)); } finally { loading.value = false; event.target.value = ''; }
        };
        const exportMachineArchiveExcel = async () => { try { const response = await axios.get('/api/v1/workshops/integration/weaving/machines/export', { responseType: 'blob' }); const blob = new Blob([response.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '织造机台档案.xlsx'; link.click(); ElMessage.success('📥 导出成功！'); } catch (error) { ElMessage.error('导出失败，请检查服务器！'); } };

        // ==========================================
        // 🗜️ 共挤产线档案（复用 lineList 全量数据 + 前端分页）
        // ==========================================
        const lineArchiveKeyword = ref('');
        const lineArchivePage = ref(1); const lineArchivePageSize = ref(10);
        const lineDialogVisible = ref(false); const lineArchiveEditMode = ref(false); const lineFileRef = ref(null);
        const emptyLineForm = () => ({ lineId: '', caliberMin: null, caliberMax: null, workshopId: '', lineStatus: '' });
        const lineForm = reactive(emptyLineForm());

        const filteredLineArchive = computed(() => {
            const kw = lineArchiveKeyword.value.trim().toLowerCase();
            if (!kw) return lineList.value;
            return lineList.value.filter(l => [l.lineId, l.workshopId, l.lineStatus, caliberLabel(l)].some(v => v != null && String(v).toLowerCase().includes(kw)));
        });
        const lineArchiveTotal = computed(() => filteredLineArchive.value.length);
        const paginatedLineArchiveList = computed(() => {
            const start = (lineArchivePage.value - 1) * lineArchivePageSize.value;
            return filteredLineArchive.value.slice(start, start + lineArchivePageSize.value);
        });
        const searchLineArchive = () => { lineArchivePage.value = 1; };

        const openAddLineArchive = () => { lineArchiveEditMode.value = false; Object.assign(lineForm, emptyLineForm()); lineDialogVisible.value = true; };
        const openEditLineArchive = (row) => {
            lineArchiveEditMode.value = true;
            Object.assign(lineForm, emptyLineForm(), {
                lineId: row.lineId, workshopId: row.workshopId || '', lineStatus: row.lineStatus || '',
                caliberMin: row.caliberMin != null ? row.caliberMin : null, caliberMax: row.caliberMax != null ? row.caliberMax : null
            });
            lineDialogVisible.value = true;
        };
        const saveLineArchive = async () => {
            if (!lineForm.lineId) { ElMessage.error('产线号不能为空！'); return; }
            const payload = {
                lineId: lineForm.lineId,
                caliberMin: lineForm.caliberMin != null ? lineForm.caliberMin : undefined,
                caliberMax: lineForm.caliberMax != null ? lineForm.caliberMax : undefined,
                workshopId: lineForm.workshopId || undefined, lineStatus: lineForm.lineStatus || undefined
            };
            try {
                const res = await axios.post('/api/v1/workshops/integration/coextrusion/lines', payload, { skipErrorHandler: true });
                ElMessage.success(typeof res.data === 'string' ? res.data : '保存成功');
                lineDialogVisible.value = false;
                loadMachinesAndLines();
            } catch (e) { ElMessage.error(errMsg(e)); }
        };
        const deleteLineArchive = async (lineId) => {
            try {
                await ElMessageBox.confirm(`确认删除产线档案 [${lineId}] ？若被历史台账引用将被拒绝。`, '警告', { type: 'warning' });
                const res = await axios.delete(`/api/v1/workshops/integration/coextrusion/lines/${encodeURIComponent(lineId)}`, { skipErrorHandler: true });
                ElMessage.success(typeof res.data === 'string' ? res.data : '删除成功');
                loadMachinesAndLines();
            } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(errMsg(e)); }
        };
        const handleLineArchiveImport = async (event) => {
            const file = event.target.files[0]; if (!file) return;
            const formData = new FormData(); formData.append('file', file);
            loading.value = true; importResult.value = null;
            try {
                const response = await axios.post('/api/v1/workshops/integration/coextrusion/lines/import', formData, { headers: { 'Content-Type': 'multipart/form-data' }, skipErrorHandler: true });
                importResult.value = response.data; lastImportSource.value = 'line-archive';
                ElMessage.success(response.data.message || '导入完成');
                loadMachinesAndLines();
            } catch (error) { ElMessage.error(errMsg(error)); } finally { loading.value = false; event.target.value = ''; }
        };
        const exportLineArchiveExcel = async () => { try { const response = await axios.get('/api/v1/workshops/integration/coextrusion/lines/export', { responseType: 'blob' }); const blob = new Blob([response.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '共挤产线档案.xlsx'; link.click(); ElMessage.success('📥 导出成功！'); } catch (error) { ElMessage.error('导出失败，请检查服务器！'); } };

        //==========================================
        // 🛒 销售订单核心
        // ==========================================
        const isOrderEditMode = ref(false); const orderList = ref([]);
        const orderHeader = reactive({ orderId: '', customerName: '', salesperson: '', orderDate: getToday(), deliveryDate: '' });
        const orderItems = ref([{ finishedPartNumber: '', productName: '', modelSpec: '', color: '', material: '', unfinishedMeters: 0, metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }]);
        const orderPage = ref(1); const orderFileRef = ref(null);

        // 🌟 全量加载（loadOrders）+ 前端过滤/分组/前端分页（CodeReview修复：服务端行级分页会把同订单明细按页边界切开，导致分组错乱/total语义错位）
        const orderKeyword = ref('');
        const orderFilters = reactive({ partNumbers: null });
        const orderPageSize = ref(10);
        // 表头筛选选项：成品零件号取自已加载订单明细
        const orderPartFilterOptions = computed(() => distinctFilterOptions(orderList.value.flatMap(o => (o.items || []).map(i => i.finishedPartNumber))));

        const resetOrderForm = () => { isOrderEditMode.value = false; Object.assign(orderHeader, { orderId: '', customerName: '', salesperson: '', orderDate: getToday(), deliveryDate: '' }); orderItems.value = [{ finishedPartNumber: '', productName: '', modelSpec: '', color: '', material: '', unfinishedMeters: 0, metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }]; };
        const calcTotal = (row) => { row.totalLength = (row.metersPerRoll * row.rollCount).toFixed(2); };
        const addOrderItem = () => { orderItems.value.push({ finishedPartNumber: '', productName: '', modelSpec: '', color: '', material: '', unfinishedMeters: 0, metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }); };
        const removeOrderItem = (index) => { if (orderItems.value.length > 1) orderItems.value.splice(index, 1); else ElMessage.warning('至少保留一行！'); };

        const loadOrders = async () => {
            try {
                const res = await axios.get('/api/v1/workshops/orders/list', { skipErrorHandler: true });
                const map = {};
                res.data.forEach(item => {
                    if (!map[item.orderId]) map[item.orderId] = { orderId: item.orderId, orderDate: item.orderDate, deliveryDate: item.deliveryDate, customerName: item.customerName, salesperson: item.salesperson, items: [] };
                    map[item.orderId].items.push(item);
                });
                orderList.value = Object.values(map);
            } catch(e) { ElMessage.error(errMsg(e)); }
            // 附加排产状态
            try {
                const schedRes = await axios.get('/api/v1/workshops/estimation/schedule-summary', { skipErrorHandler: true });
                if (Array.isArray(schedRes.data)) {
                    orderList.value.forEach(o => {
                        const sched = schedRes.data.find(s => s.orderId === o.orderId);
                        o.scheduleStatus = sched ? '已排产' : '未排产';
                        o.plannedStartDate = sched ? sched.plannedStartDate : null;
                        o.plannedEndDate = sched ? sched.plannedEndDate : null;
                    });
                }
            } catch (e) {
                // 排产状态获取失败不影响订单列表显示
                console.warn('排产状态获取失败:', e.message);
            }
        };

        // 🌟 订单列表页：前端过滤（keyword 模糊匹配订单号/零件号/品名/客户；partNumbers 表头筛选匹配明细含该零件号的订单）→ 按 orderId 分组（loadOrders 已分组）→ 前端分页；total = 分组后订单数
        const groupedOrders = computed(() => {
            const kw = (orderKeyword.value || '').trim().toLowerCase();
            const fpn = orderFilters.partNumbers;
            let list = orderList.value;
            if (fpn) list = list.filter(o => (o.items || []).some(i => i.finishedPartNumber === fpn));
            if (kw) list = list.filter(o =>
                (o.orderId || '').toLowerCase().includes(kw) ||
                (o.customerName || '').toLowerCase().includes(kw) ||
                (o.items || []).some(i => (i.finishedPartNumber || '').toLowerCase().includes(kw) || (i.productName || '').toLowerCase().includes(kw)));
            return list;
        });
        const orderTotal = computed(() => groupedOrders.value.length);
        const paginatedOrderList = computed(() => {
            const list = groupedOrders.value;
            const maxPage = Math.max(1, Math.ceil(list.length / orderPageSize.value));
            const p = Math.min(orderPage.value, maxPage);
            const start = (p - 1) * orderPageSize.value;
            return list.slice(start, start + orderPageSize.value);
        });
        const searchOrders = () => { orderPage.value = 1; };
        const handleOrderFilterChange = ({ property, filters }) => {
            if (property === 'partNumbers') orderFilters.partNumbers = (filters && filters.length > 0) ? filters[0] : null;
            orderPage.value = 1;
        };
        const onOrderSizeChange = () => { orderPage.value = 1; };
        const onOrderPageChange = () => { /* paginatedOrderList 为 computed，自动重算 */ };

        const submitOrder = async () => {
            if (!orderHeader.orderId) { ElMessage.error('请填写订单号！'); return; }
            loading.value = true; const payload = orderItems.value.map(item => ({ ...orderHeader, ...item }));
            try {
                if (isOrderEditMode.value) await axios.put(`/api/v1/workshops/orders/${orderHeader.orderId}`, payload, { skipErrorHandler: true });
                else await axios.post('/api/v1/workshops/orders/batch', payload, { skipErrorHandler: true });
                ElMessage.success('操作成功！'); loadOrders(); resetOrderForm();
            } catch (error) { ElMessage.error(errMsg(error)); } finally { loading.value = false; }
        };

        const editOrder = (row) => {
            isOrderEditMode.value = true;
            Object.assign(orderHeader, { orderId: row.orderId, customerName: row.customerName, salesperson: row.salesperson, orderDate: row.orderDate, deliveryDate: row.deliveryDate });
            orderItems.value = JSON.parse(JSON.stringify(row.items));
            orderItems.value.forEach(item => calcTotal(item)); window.scrollTo({ top: 0, behavior: 'smooth' });
        };
        const deleteOrder = async (orderId) => { try { await ElMessageBox.confirm('数据将永久删除，确认执行？', '警告', { type: 'warning' }); await axios.delete(`/api/v1/workshops/orders/${orderId}`, { skipErrorHandler: true }); ElMessage.success('订单已删除'); loadOrders(); if (orderHeader.orderId === orderId) resetOrderForm(); } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(errMsg(e)); } };
        const exportOrderExcel = async () => { try { const res = await axios.get('/api/v1/workshops/orders/export', { responseType: 'blob' }); const blob = new Blob([res.data]); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '生产订单排产明细.xlsx'; link.click(); ElMessage.success('📥 导出成功！'); } catch(e) { ElMessage.error('导出失败'); } };
        const handleOrderImport = async (e) => { const file = e.target.files[0]; if (!file) return; const fd = new FormData(); fd.append('file', file); loading.value = true; try { const res = await axios.post('/api/v1/workshops/orders/import', fd, { headers: { 'Content-Type': 'multipart/form-data' }, skipErrorHandler: true }); ElMessage.success(res.data); loadOrders(); } catch(err) { ElMessage.error(errMsg(err)); } finally { loading.value = false; e.target.value = ''; } };

        // ==========================================
        // 🔮 询单预估
        // ==========================================
        const inquiryForm = reactive({
            customerName: '',
            plannedProductionDays: 30,
            globalBufferDays: 3,
            weavingReserveDays: 0,
            items: [{ finishedPartNumber: '', productName: '', modelSpec: '', metersPerRoll: 0, rollCount: 0 }]
        });
        const inquiryResult = ref(null);

        const addInquiryItem = () => {
            inquiryForm.items.push({ finishedPartNumber: '', productName: '', modelSpec: '', metersPerRoll: 0, rollCount: 0 });
        };
        const removeInquiryItem = (index) => {
            if (inquiryForm.items.length > 1) inquiryForm.items.splice(index, 1);
            else ElMessage.warning('至少保留一行！');
        };

        const fetchInquiry = async () => {
            if (!inquiryForm.items.some(i => i.finishedPartNumber)) {
                ElMessage.warning('请至少填写一个成品零件号！');
                return;
            }
            loading.value = true;
            inquiryResult.value = null;
            try {
                const payload = {
                    plannedProductionDays: inquiryForm.plannedProductionDays,
                    globalBufferDays: inquiryForm.globalBufferDays,
                    weavingReserveDays: inquiryForm.weavingReserveDays || 0,
                    items: inquiryForm.items
                        .filter(i => i.finishedPartNumber)
                        .map(i => ({
                            finishedPartNumber: i.finishedPartNumber,
                            productName: i.productName,
                            modelSpec: i.modelSpec,
                            totalLength: i.metersPerRoll * i.rollCount,
                            metersPerRoll: i.metersPerRoll,
                            rollCount: i.rollCount
                        })),
                    resourceOverrides: inquiryOverrides.value.length > 0 ? inquiryOverrides.value : undefined
                };
                const res = await axios.post('/api/v1/workshops/estimation/inquiry', payload, { skipErrorHandler: true });
                inquiryResult.value = res.data;
                syncInquiryResCounts();
                estResult.value = {
                    orderId: '询单预估',
                    details: res.data.details || [],
                    overallStartDate: res.data.overallStartDate,
                    overallEndDate: res.data.overallEndDate,
                    totalDays: res.data.totalDays,
                    conflictWarnings: res.data.conflictWarnings || []
                };
                ElMessage.success('🔮 询单预估计算完毕！');
            } catch (error) {
                const msg = error.response?.data?.message || error.response?.data || '';
                const msgStr = typeof msg === 'string' ? msg : '';
                if (msgStr.startsWith('MISSING_CAPACITY:')) {
                    // 与排产页共用同一套熔断补录弹窗（四段格式：前缀:成品:带坯:缺失字段）
                    const parts = msgStr.split(':');
                    capacityPrompt.finishedPartNumber = parts[1];
                    capacityPrompt.tapePartNumber = parts[2];
                    capacityPrompt.missingField = parts[3] || '';
                    manualCap.weaving = 1000; manualCap.coex = 1500; manualCap.saveToProcess = false;
                    capacitySource.value = 'inquiry';
                    capacityDialogVisible.value = true;
                } else {
                    ElMessage.error(msgStr || '询单预估失败');
                }
            } finally {
                loading.value = false;
            }
        };

        // ==========================================
        // 🎛️ 任务#24：询单独立增减织造机台/共挤产线（details 已为资源行模型）
        // ==========================================
        // 每个询单条目一组的数量步进器状态：{ finishedPartNumber, machineCount, lineCount }
        const inquiryResCounts = ref([]);
        let inquiryRecalcTimer = null;

        // 预估完成后初始化/补齐各条目步进器值：已改过的保留用户值，新条目取响应推荐值
        const syncInquiryResCounts = () => {
            if (!inquiryResult.value) return;
            const parts = inquiryForm.items.filter(i => i.finishedPartNumber).map(i => i.finishedPartNumber);
            inquiryResCounts.value = parts.map(pn => {
                const exist = inquiryResCounts.value.find(c => c.finishedPartNumber === pn);
                return exist || {
                    finishedPartNumber: pn,
                    machineCount: inquiryResult.value.recommendedMachineCount || 1,
                    lineCount: inquiryResult.value.recommendedLineCount || 1
                };
            });
        };

        // 组装 resourceOverrides：数量 + 既有人工产能（熔断补录保留） + 当前 details 资源行已选指派
        const rebuildInquiryOverrides = () => {
            const details = (inquiryResult.value && inquiryResult.value.details) || [];
            inquiryOverrides.value = inquiryResCounts.value
                .filter(c => c.finishedPartNumber)
                .map(c => {
                    const existing = inquiryOverrides.value.find(o => o.finishedPartNumber === c.finishedPartNumber) || {};
                    const assignedMachineIds = details
                        .filter(d => d.finishedPartNumber === c.finishedPartNumber && d.plannedMachine && (d.allocationType ? d.allocationType === 'weaving' : !!d.weavingStart))
                        .map(d => d.plannedMachine);
                    const assignedLineIds = details
                        .filter(d => d.finishedPartNumber === c.finishedPartNumber && d.plannedLine && (d.allocationType ? d.allocationType === 'coex' : !!d.coexStart))
                        .map(d => d.plannedLine);
                    return {
                        finishedPartNumber: c.finishedPartNumber,
                        machineCount: c.machineCount || undefined,
                        lineCount: c.lineCount || undefined,
                        manualWeavingCapacity: existing.manualWeavingCapacity,
                        manualCoexCapacity: existing.manualCoexCapacity,
                        assignedMachineIds: assignedMachineIds.length > 0 ? assignedMachineIds : undefined,
                        assignedLineIds: assignedLineIds.length > 0 ? assignedLineIds : undefined
                    };
                });
        };

        // 🌟 500ms 防抖（同 debouncedApplyAdjustments 模式）：步进器/下拉变更 → 重建 overrides → fetchInquiry 重算
        const debouncedInquiryRecalc = () => {
            clearTimeout(inquiryRecalcTimer);
            inquiryRecalcTimer = setTimeout(() => { rebuildInquiryOverrides(); fetchInquiry(); }, 500);
        };

        // 机台/产线下拉变更后回写指派 id 并防抖重算
        const onInquiryAssignChange = (row) => {
            row.assignedMachineIds = row.plannedMachine ? [row.plannedMachine] : undefined;
            row.assignedLineIds = row.plannedLine ? [row.plannedLine] : undefined;
            debouncedInquiryRecalc();
        };

        // 🌟 性能优化：提取共享 inquiryGanttBounds
        const inquiryGanttBounds = computed(() => {
            if (!inquiryResult.value || !inquiryResult.value.details) return null;
            let minTime = new Date('2099-01-01').getTime();
            let maxTime = new Date('2000-01-01').getTime();
            inquiryResult.value.details.forEach(d => {
                if (d.weavingStart) minTime = Math.min(minTime, new Date(d.weavingStart).getTime());
                if (d.weavingEnd) maxTime = Math.max(maxTime, new Date(d.weavingEnd).getTime());
                if (d.coexStart) minTime = Math.min(minTime, new Date(d.coexStart).getTime());
                if (d.coexEnd) maxTime = Math.max(maxTime, new Date(d.coexEnd).getTime());
            });
            const span = maxTime - minTime;
            minTime -= span * 0.05; maxTime += span * 0.05;
            return { minTime, maxTime, span: maxTime - minTime };
        });

        const inquiryGanttTimeline = computed(() => {
            if (!inquiryGanttBounds.value) return [];
            const { minTime, maxTime, span } = inquiryGanttBounds.value;
            const markers = [];
            const days = span / (1000 * 60 * 60 * 24);
            let step = 1;
            if (days > 15) step = Math.ceil(days / 10);
            for (let t = minTime; t <= maxTime; t += step * 24 * 3600 * 1000) {
                const dateObj = new Date(t);
                markers.push({ left: ((t - minTime) / span * 100) + '%', label: `${dateObj.getMonth() + 1}月${dateObj.getDate()}日` });
            }
            return markers;
        });

        // 需求6：构建共挤甘特条上的换带坯竖线标记（百分比 = (eventTime - rowStart) / (rowEnd - rowStart)，超出该条范围的事件过滤不渲染）
        const buildTapeChangeMarkers = (d, s, e) => {
            if (!Array.isArray(d.tapeChangeEvents) || d.tapeChangeEvents.length === 0 || !(e > s)) return [];
            const markers = [];
            d.tapeChangeEvents.forEach(ev => {
                const t = ev && ev.time ? new Date(ev.time).getTime() : NaN;
                if (isNaN(t) || t <= s || t >= e) return;
                markers.push({
                    left: (((t - s) / (e - s)) * 100).toFixed(2) + '%',
                    tip: `更换带坯：${ev.fromMachineId} → ${ev.toMachineId} @ ${(ev.time || '').replace('T', ' ')}`
                });
            });
            return markers;
        };

        const inquiryGanttRows = computed(() => {
            if (!inquiryGanttBounds.value || !inquiryResult.value || !inquiryResult.value.details) return [];
            const { minTime, span: totalSpan } = inquiryGanttBounds.value;
            const rowsMap = new Map();
            rowsMap.set('W_UNASSIGNED', { id: 'W_UNASSIGNED', label: '🧶 织造 (待指派)', tasks: [] });
            machineList.value.forEach(m => rowsMap.set('W_' + m.machineId, { id: 'W_' + m.machineId, label: '机台 ' + m.machineId + (caliberLabel(m) ? ' [' + caliberLabel(m) + ']' : '') + '#', tasks: [] }));
            rowsMap.set('C_UNASSIGNED', { id: 'C_UNASSIGNED', label: '🗜️ 共挤 (待指派)', tasks: [] });
            lineList.value.forEach(l => rowsMap.set('C_' + l.lineId, { id: 'C_' + l.lineId, label: '产线 ' + l.lineId + (caliberLabel(l) ? ' [' + caliberLabel(l) + ']' : '') + '#', tasks: [] }));
            const colors = ['#3b82f6', '#8b5cf6', '#f59e0b', '#10b981', '#ec4899', '#f43f5e'];
            inquiryResult.value.details.forEach((d, idx) => {
                const color = colors[idx % colors.length];
                if (d.weavingStart) {
                    const s = new Date(d.weavingStart).getTime(); const e = new Date(d.weavingEnd).getTime();
                    const task = {
                        ...d, uid: 'inq_' + idx + '_w', rawStart: d.weavingStart.replace('T',' '), rawEnd: d.weavingEnd.replace('T',' '),
                        left: ((s - minTime) / totalSpan * 100) + '%', width: ((e - s) / totalSpan * 100) + '%',
                        color, label: `成品:${d.finishedPartNumber} (${(d.tapeMetersNeed || 0).toFixed(0)}m)`, typeStr: '织造排期'
                    };
                    const rId = d.plannedMachine ? 'W_' + d.plannedMachine : 'W_UNASSIGNED';
                    if (rowsMap.has(rId)) rowsMap.get(rId).tasks.push(task);
                }
                if (d.coexStart) {
                    const s = new Date(d.coexStart).getTime(); const e = new Date(d.coexEnd).getTime();
                    const task = {
                        ...d, uid: 'inq_' + idx + '_c', rawStart: d.coexStart.replace('T',' '), rawEnd: d.coexEnd.replace('T',' '),
                        left: ((s - minTime) / totalSpan * 100) + '%', width: ((e - s) / totalSpan * 100) + '%',
                        color, label: `成品:${d.finishedPartNumber} (${(d.finishedMeters || 0).toFixed(0)}m)`, typeStr: '共挤排期',
                        events: buildTapeChangeMarkers(d, s, e)
                    };
                    const rId = d.plannedLine ? 'C_' + d.plannedLine : 'C_UNASSIGNED';
                    if (rowsMap.has(rId)) rowsMap.get(rId).tasks.push(task);
                }
            });
            return Array.from(rowsMap.values()).filter(r => r.tasks.length > 0);
        });

        // ==========================================
        // 📊 智能排产大盘 (APS核心引擎)
        // ==========================================
        const estForm = reactive({
            orderId: '',
            itemAdjustments: [],
            globalBufferDays: 3,
            weavingReserveDays: 0
        });
        const estResult = ref(null);

        const weavingScheduleDetails = computed(() => {
            if (!estResult.value || !estResult.value.details) return [];
            return estResult.value.details.filter(d => d.allocationType === 'weaving');
        });

        const coexScheduleDetails = computed(() => {
            if (!estResult.value || !estResult.value.details) return [];
            return estResult.value.details.filter(d => d.allocationType === 'coex');
        });

        // 🌟 物料消耗汇总：经/纬线用量取自织造行（按缺口米数），用胶量取自共挤行（按成品米数），避免织造/共挤行重复计数
        const buildMaterialSummary = (details) => {
            if (!details || !details.length) return null;
            const weavingRows = details.filter(d => d.allocationType === 'weaving');
            const coexRows = details.filter(d => d.allocationType === 'coex');
            const sum = (rows, key) => rows.reduce((acc, r) => acc + (Number(r[key]) || 0), 0);
            const warpTotalKg = sum(weavingRows, 'warpTotalWeightKg');
            const weft3000DTotalKg = sum(weavingRows, 'weft3000DTotalWeightKg');
            const weft2000DTotalKg = sum(weavingRows, 'weft2000DTotalWeightKg');
            const glueTotalKg = sum(coexRows, 'glueTotalKg');
            const warpSpec = weavingRows.find(r => r.warpSpec)?.warpSpec || null;
            const weftSpec3000D = weavingRows.find(r => r.weftSpec3000D)?.weftSpec3000D || null;
            const weftSpec2000D = weavingRows.find(r => r.weftSpec2000D)?.weftSpec2000D || null;
            const materialType = coexRows.find(r => r.materialType)?.materialType || null;
            const hasData = warpTotalKg > 0 || weft3000DTotalKg > 0 || weft2000DTotalKg > 0 || glueTotalKg > 0;
            return { warpTotalKg, weft3000DTotalKg, weft2000DTotalKg, glueTotalKg, warpSpec, weftSpec3000D, weftSpec2000D, materialType, hasData };
        };

        const estMaterialSummary = computed(() => {
            if (!estResult.value || !estResult.value.details) return null;
            return buildMaterialSummary(estResult.value.details);
        });

        const inquiryMaterialSummary = computed(() => {
            if (!inquiryResult.value || !inquiryResult.value.details) return null;
            return buildMaterialSummary(inquiryResult.value.details);
        });

        // 口径候选过滤 helper：按行 candidateMachineIds/candidateLineIds 过滤下拉选项；后端未返回时回退全量列表
        const rowCandidateMachines = (row) => {
            if (!row.candidateMachineIds || !row.candidateMachineIds.length) return machineList.value;
            return machineList.value.filter(m => row.candidateMachineIds.includes(m.machineId));
        };
        const rowCandidateLines = (row) => {
            if (!row.candidateLineIds || !row.candidateLineIds.length) return lineList.value;
            return lineList.value.filter(l => row.candidateLineIds.includes(l.lineId));
        };
        const capacityDialogVisible = ref(false);
        const capacityPrompt = reactive({ finishedPartNumber: '', tapePartNumber: '', missingField: '' });
        const manualCap = reactive({ weaving: 0, coex: 0, saveToProcess: false });
        // 熔断弹窗触发来源：estimation(排产) / inquiry(询单)，决定补录后重新推演的入口
        const capacitySource = ref('estimation');
        const inquiryOverrides = ref([]);
        const capacityFieldLabel = computed(() => capacityPrompt.missingField === 'coexMaxDailyOutput' ? '共挤最大日产 (coexMaxDailyOutput)'
            : capacityPrompt.missingField === 'weavingStandardDailyOutput' ? '织造标准日产 (weavingStandardDailyOutput)'
            : '未指定（将同时回写两项产能）');

        const orderSuggestions = computed(() => {
            if (!estForm.orderId) return [];
            return orderList.value
                .filter(o => o.orderId && o.orderId.includes(estForm.orderId))
                .map(o => ({ value: o.orderId, label: `${o.orderId} - ${o.customerName || ''}` }));
        });

        const multiOrderMode = ref(false);
        const selectedOrderIds = ref([]);
        const multiOrderResult = ref(null);

        const fetchMultiOrderSchedule = async () => {
            if (selectedOrderIds.value.length === 0) {
                ElMessage.warning('请至少选择一个订单！');
                return;
            }
            loading.value = true;
            try {
                const res = await axios.post('/api/v1/workshops/estimation/preview-multi', {
                    orderIds: selectedOrderIds.value,
                    globalBufferDays: estForm.globalBufferDays,
                    weavingReserveDays: estForm.weavingReserveDays || 0
                }, { skipErrorHandler: true });
                const data = res.data;
                multiOrderResult.value = data;
                // 适配为与单订单一致的格式，使现有甘特图渲染逻辑可以复用
                estResult.value = {
                    orderId: '多订单合并排产',
                    details: data.results || [],
                    overallStartDate: data.overallStartDate,
                    overallEndDate: data.overallEndDate,
                    totalDays: data.overallStartDate && data.overallEndDate ? 
                        Math.ceil((new Date(data.overallEndDate) - new Date(data.overallStartDate)) / (1000*60*60*24)) + 1 : 0,
                    conflictWarnings: data.conflictWarnings || []
                };
                ElMessage.success('多订单排产推演完毕！');
            } catch (error) {
                const msg = error.response?.data?.message || error.response?.data || '';
                ElMessage.error(typeof msg === 'string' && msg ? msg : '多订单排产失败，请检查订单数据完整性');
            } finally {
                loading.value = false;
            }
        };

        const loadScheduleSummary = async () => {
            try {
                const res = await axios.get('/api/v1/workshops/estimation/schedule-summary', { skipErrorHandler: true });
                scheduleSummaryMap.value = {};
                if (Array.isArray(res.data)) {
                    res.data.forEach(item => {
                        scheduleSummaryMap.value[item.orderId] = item;
                    });
                }
            } catch (e) { ElMessage.error(errMsg(e)); }
        };

        const scheduleSummaryMap = ref({});

        const fetchInitialDraft = async () => {
            if (!estForm.orderId) { ElMessage.warning('请先输入订单号！'); return; }
            loading.value = true; estResult.value = null;
            try {
                const res = await axios.post('/api/v1/workshops/estimation/preview', estForm, { skipErrorHandler: true });
                estResult.value = res.data;
                ElMessage.info('排产草稿及时间轴已就绪！');
            } catch (error) {
                const msg = error.response?.data?.message || error.response?.data || '';
                const msgStr = typeof msg === 'string' ? msg : '';
                if (msgStr.startsWith("MISSING_PROCESS:")) {
                    const pn = msgStr.split(":")[1];
                    ElMessageBox.warning(`未找到成品 [${pn}] 的工艺路线定义，请先前往维护绑定关系！`, '防呆拦截');
                    openAddProcess();
                    processForm.finishedPartNumber = pn;
                    activeMenu.value = 'process';
                } else if (msgStr.startsWith("MISSING_CAPACITY:")) {
                    const parts = msgStr.split(":");
                    capacityPrompt.finishedPartNumber = parts[1];
                    capacityPrompt.tapePartNumber = parts[2];
                    // 🌟 第四段为新增缺失字段名（weavingStandardDailyOutput / coexMaxDailyOutput），兼容旧三段格式
                    capacityPrompt.missingField = parts[3] || '';
                    // 🌟 默认给合理初始值，避免用户未修改就提交时传入 0 导致死循环
                    manualCap.weaving = 1000; manualCap.coex = 1500; manualCap.saveToProcess = false;
                    capacitySource.value = 'estimation';
                    capacityDialogVisible.value = true;
                } else if (msgStr) {
                    ElMessage.error(msgStr);
                } else {
                    ElMessage.error('排产推演失败，请检查订单数据是否完整');
                }
            } finally { loading.value = false; }
        };

        // 🌟 人工补录产能回写工艺库（POST /process/save），字段名取自异常消息第四段
        const saveCapacityToProcessLib = async () => {
            if (processList.value.length === 0) await loadProcesses();
            const proc = processList.value.find(p => p.finishedPartNumber === capacityPrompt.finishedPartNumber);
            if (!proc) {
                ElMessage.warning('工艺库中未找到该成品工艺，无法回写（请先在工艺页建立绑定）');
                return;
            }
            const payload = { ...proc };
            const field = capacityPrompt.missingField;
            if (field === 'weavingStandardDailyOutput') payload.weavingStandardDailyOutput = manualCap.weaving;
            else if (field === 'coexMaxDailyOutput') payload.coexMaxDailyOutput = manualCap.coex;
            else { payload.weavingStandardDailyOutput = manualCap.weaving; payload.coexMaxDailyOutput = manualCap.coex; }
            await axios.post('/api/v1/workshops/integration/process/save', payload, { skipErrorHandler: true });
            await loadProcesses();
            ElMessage.success('✅ 产能补录值已同步写入工艺库！');
        };

        const submitManualCapacity = async () => {
            // 🌟 防呆校验：产能必须 > 0，否则打回重填，终止死循环
            if (!manualCap.weaving || manualCap.weaving <= 0 || !manualCap.coex || manualCap.coex <= 0) {
                ElMessage.warning('织造产能和共挤产能均必须大于 0，请重新填写！');
                return;
            }
            // 🌟 勾选“同时保存到工艺库”：先经 /process/save 回写对应缺失字段
            if (manualCap.saveToProcess) {
                try { await saveCapacityToProcessLib(); } catch (e) { ElMessage.error('回写工艺库失败: ' + errMsg(e)); return; }
            }
            if (capacitySource.value === 'inquiry') {
                // 询单链路：累积式写入 resourceOverrides 后重新预估
                const idx = inquiryOverrides.value.findIndex(o => o.finishedPartNumber === capacityPrompt.finishedPartNumber);
                const ov = { finishedPartNumber: capacityPrompt.finishedPartNumber, manualWeavingCapacity: manualCap.weaving, manualCoexCapacity: manualCap.coex };
                if (idx >= 0) inquiryOverrides.value[idx] = ov; else inquiryOverrides.value.push(ov);
                capacityDialogVisible.value = false;
                fetchInquiry();
                return;
            }
            // 🌟 累积式追加：多明细订单时，已录入的零件调整量不丢失
            const existingIdx = estForm.itemAdjustments.findIndex(a => a.finishedPartNumber === capacityPrompt.finishedPartNumber);
            const newAdj = {
                finishedPartNumber: capacityPrompt.finishedPartNumber,
                manualWeavingCapacity: manualCap.weaving,
                manualCoexCapacity: manualCap.coex
            };
            if (existingIdx >= 0) {
                estForm.itemAdjustments[existingIdx] = newAdj;
            } else {
                estForm.itemAdjustments.push(newAdj);
            }
            capacityDialogVisible.value = false;
            fetchInitialDraft();
        };

        const commitFinalScheduleToDb = async () => {
            if (!estResult.value) return;
            try {
                await ElMessageBox.confirm('确认以当前甘特图节点正式下发任务？', '排产复核', { type: 'success' });
                const res = await axios.post('/api/v1/workshops/estimation/commit', estResult.value, { skipErrorHandler: true });
                ElMessage.success(res.data); estResult.value = null; estForm.itemAdjustments = [];
            } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errMsg(error)); }
        };

        // 🌟 性能优化：提取共享 ganttBounds，避免 ganttTimeline 和 ganttRows 重复遍历
        const ganttBounds = computed(() => {
            if (!estResult.value || !estResult.value.details) return null;
            let minTime = new Date('2099-01-01').getTime();
            let maxTime = new Date('2000-01-01').getTime();
            estResult.value.details.forEach(d => {
                if (d.weavingStart) minTime = Math.min(minTime, new Date(d.weavingStart).getTime());
                if (d.weavingEnd) maxTime = Math.max(maxTime, new Date(d.weavingEnd).getTime());
                if (d.coexStart) minTime = Math.min(minTime, new Date(d.coexStart).getTime());
                if (d.coexEnd) maxTime = Math.max(maxTime, new Date(d.coexEnd).getTime());
            });
            const span = maxTime - minTime;
            minTime -= span * 0.05; maxTime += span * 0.05;
            return { minTime, maxTime, span: maxTime - minTime };
        });

        const ganttTimeline = computed(() => {
            if (!ganttBounds.value) return [];
            const { minTime, maxTime, span } = ganttBounds.value;
            const markers = [];
            const days = span / (1000 * 60 * 60 * 24);
            let step = 1;
            if (days > 15) step = Math.ceil(days / 10);
            for (let t = minTime; t <= maxTime; t += step * 24 * 3600 * 1000) {
                const dateObj = new Date(t);
                markers.push({
                    left: ((t - minTime) / span * 100) + '%',
                    label: `${dateObj.getMonth() + 1}月${dateObj.getDate()}日`
                });
            }
            return markers;
        });

        const ganttRows = computed(() => {
            if (!ganttBounds.value || !estResult.value || !estResult.value.details) return [];
            const { minTime, span: totalSpan } = ganttBounds.value;
            const rowsMap = new Map();
            rowsMap.set('W_UNASSIGNED', { id: 'W_UNASSIGNED', label: '🧶 织造 (待指派)', tasks: [] });
            machineList.value.forEach(m => rowsMap.set('W_' + m.machineId, { id: 'W_' + m.machineId, label: '机台 ' + m.machineId + '#', tasks: [] }));
            rowsMap.set('C_UNASSIGNED', { id: 'C_UNASSIGNED', label: '🗜️ 共挤 (待指派)', tasks: [] });
            lineList.value.forEach(l => rowsMap.set('C_' + l.lineId, { id: 'C_' + l.lineId, label: '产线 ' + l.lineId + '#', tasks: [] }));

            const colors = ['#3b82f6', '#8b5cf6', '#f59e0b', '#10b981', '#ec4899', '#f43f5e'];
            estResult.value.details.forEach((d, idx) => {
                const color = colors[idx % colors.length];
                if (d.weavingStart) {
                    const s = new Date(d.weavingStart).getTime(); const e = new Date(d.weavingEnd).getTime();
                    const task = {
                        ...d, rawStart: d.weavingStart.replace('T',' '), rawEnd: d.weavingEnd.replace('T',' '),
                        left: ((s - minTime) / totalSpan * 100) + '%', width: ((e - s) / totalSpan * 100) + '%',
                        color: color, label: `订单:${d.orderId} | 带坯:${d.tapePartNumber} (${(d.tapeMetersNeed || 0).toFixed(0)}m)`,
                        typeStr: '织造排期'
                    };
                    const rId = d.plannedMachine ? 'W_' + d.plannedMachine : 'W_UNASSIGNED';
                    if(rowsMap.has(rId)) rowsMap.get(rId).tasks.push(task);
                }
                if (d.coexStart) {
                    const s = new Date(d.coexStart).getTime(); const e = new Date(d.coexEnd).getTime();
                    const task = {
                        ...d, rawStart: d.coexStart.replace('T',' '), rawEnd: d.coexEnd.replace('T',' '),
                        left: ((s - minTime) / totalSpan * 100) + '%', width: ((e - s) / totalSpan * 100) + '%',
                        color: color, label: `订单:${d.orderId} | 成品:${d.finishedPartNumber} (${(d.finishedMeters || 0).toFixed(0)}m)`,
                        typeStr: '共挤排期',
                        events: buildTapeChangeMarkers(d, s, e)
                    };
                    const rId = d.plannedLine ? 'C_' + d.plannedLine : 'C_UNASSIGNED';
                    if(rowsMap.has(rId)) rowsMap.get(rId).tasks.push(task);
                }
            });
            return Array.from(rowsMap.values()).filter(r => r.tasks.length > 0);
        });

        // ==========================================
        // 📊 核心数据抓取：关联订单与工艺推演
        // ==========================================
        const cleanId = (id) => String(id).replace(/[#线]/g, '').trim();
        const getStatusClass = (status) => { if (!status) return 'status-other'; if (status.includes('产')) return 'status-producing'; if (status.includes('闲')) return 'status-idle'; if (status.includes('停')) return 'status-stopped'; if (status.includes('修')) return 'status-maintenance'; return 'status-other'; };

        // 🌟 性能优化：预构建日志索引 Map，避免 O(M*N) 重复 filter
        const weavingLogsByMachine = computed(() => {
            const map = {};
            weavingLogList.value.forEach(log => {
                const id = cleanId(log.machineId);
                if (!map[id]) map[id] = [];
                map[id].push(log);
            });
            Object.values(map).forEach(logs => logs.sort((a, b) => new Date(b.entryDate) - new Date(a.entryDate)));
            return map;
        });

        const coexLogsByLine = computed(() => {
            const map = {};
            coexLogList.value.forEach(log => {
                const id = cleanId(log.lineId);
                if (!map[id]) map[id] = [];
                map[id].push(log);
            });
            Object.values(map).forEach(logs => logs.sort((a, b) => new Date(b.entryDate) - new Date(a.entryDate)));
            return map;
        });

        const computedMachineStatusOptimized = (machineId, logs, globalMaxTime) => {
            if (!logs || logs.length === 0) return '空闲';
            // logs 已预排序（最新在前）
            const latestLog = logs[0];
            const latestLogTime = new Date(latestLog.entryDate + 'T00:00:00').getTime();
            const ONE_DAY = 24 * 3600 * 1000;
            const hasRecentData = (globalMaxTime - latestLogTime) <= ONE_DAY;
            const r = latestLog.remarks || '';
            if (r.includes('了机')) return '空闲';
            if (r.includes('开头机')) return '在产';
            return hasRecentData ? '在产' : '空闲';
        };

        // 🌟 性能优化：使用预构建索引 + 预计算 globalMaxTime
        const machineStatusMap = computed(() => {
            const map = {};
            let globalMaxTime = 0;
            weavingLogList.value.forEach(l => {
                const t = new Date(l.entryDate + 'T00:00:00').getTime();
                if (t > globalMaxTime) globalMaxTime = t;
            });
            machineList.value.forEach(m => {
                map[cleanId(m.machineId)] = computedMachineStatusOptimized(m.machineId, weavingLogsByMachine.value[cleanId(m.machineId)] || [], globalMaxTime);
            });
            return map;
        });
        const lineStatusMap = computed(() => {
            const map = {};
            let globalMaxTime = 0;
            coexLogList.value.forEach(l => {
                const t = new Date(l.entryDate + 'T00:00:00').getTime();
                if (t > globalMaxTime) globalMaxTime = t;
            });
            lineList.value.forEach(l => {
                map[cleanId(l.lineId)] = computedMachineStatusOptimized(l.lineId, coexLogsByLine.value[cleanId(l.lineId)] || [], globalMaxTime);
            });
            return map;
        });

        // 🌟 核心算法升级：通过带坯寻找最新订单，并将总任务量平均分配给所有在产该带坯的机台
        const getWeavingOrderProgress = (tapePn, machineId) => {
            if (!tapePn || tapePn === '未知') return { orderId: '无订单', finishedPn: '', total: 0, accum: 0 };

            const matchedFinishedPns = processList.value.filter(p => p.tapePartNumber === tapePn).map(p => p.finishedPartNumber);

            let currentOrder = null;
            let currentOrderItem = null;
            let latestOrderDate = -1;

            orderList.value.forEach(o => {
                if (!o.items) return;
                o.items.forEach(it => {
                    if (matchedFinishedPns.includes(it.finishedPartNumber)) {
                        const t = new Date(o.orderDate || 0).getTime();
                        if (t > latestOrderDate) {
                            latestOrderDate = t;
                            currentOrder = o;
                            currentOrderItem = it;
                        }
                    }
                });
            });

            let accum = 0;
            let total = 0;
            let orderId = '无订单';
            let finishedPn = '';

            // 动态探测当前有几台织造机正在“并发”做这个带坯
            let activeCount = 0;
            machineList.value.forEach(m => {
                if (machineStatusMap.value[cleanId(m.machineId)] === '在产') {
                    const logs = weavingLogsByMachine.value[cleanId(m.machineId)] || [];
                    if (logs[0] && logs[0].tapePartNumber === tapePn) activeCount++;
                }
            });
            if (activeCount === 0) activeCount = 1;

            if (currentOrder) {
                orderId = currentOrder.orderId;
                finishedPn = currentOrderItem.finishedPartNumber;
                let rawTotal = currentOrderItem.totalLength > 0 ? currentOrderItem.totalLength : (currentOrderItem.metersPerRoll * currentOrderItem.rollCount);
                if (!rawTotal) rawTotal = 1000;
                // 🌟 将该订单的目标需求量，平均分配给并发生产的所有机台
                total = rawTotal / activeCount;

                // 🌟 截断历史数据：只累加当前特定机台自己，在该订单下达日期之后的产量
                const orderTime = new Date(currentOrder.orderDate + 'T00:00:00').getTime();
                accum = (weavingLogsByMachine.value[cleanId(machineId)] || [])
                    .filter(l => l.tapePartNumber === tapePn && new Date(l.entryDate + 'T00:00:00').getTime() >= orderTime)
                    .reduce((sum, l) => sum + (l.capacityPerDay || 0), 0);
            } else {
                const taskLogs = (weavingLogsByMachine.value[cleanId(machineId)] || []).filter(l => l.tapePartNumber === tapePn);
                accum = taskLogs.reduce((sum, l) => sum + (l.capacityPerDay || 0), 0);
                total = accum > 0 ? accum * 1.5 : 1000;
            }

            return { orderId, finishedPn, total, accum };
        };

        // 🌟 同理：共挤线多线并产均分任务量
        const getCoexOrderProgress = (orderId, finishedPn, lineId) => {
            let total = 0;
            let accum = 0;

            // 动态探测并发产线条数
            let activeCount = 0;
            lineList.value.forEach(l => {
                if (lineStatusMap.value[cleanId(l.lineId)] === '在产') {
                    const logs = coexLogsByLine.value[cleanId(l.lineId)] || [];
                    if (logs[0] && logs[0].orderNumber === orderId && logs[0].finishedPartNumber === finishedPn) activeCount++;
                }
            });
            if (activeCount === 0) activeCount = 1;

            orderList.value.forEach(o => {
                if (o.orderId === orderId) {
                    const it = o.items.find(i => i.finishedPartNumber === finishedPn);
                    if (it) {
                        let rawTotal = it.totalLength > 0 ? it.totalLength : (it.metersPerRoll * it.rollCount);
                        total = rawTotal / activeCount; // 🌟 均分目标需求量
                    }
                }
            });

            // 🌟 只统计特定产线自己的产量
            const taskLogs = (coexLogsByLine.value[cleanId(lineId)] || []).filter(log => log.orderNumber === orderId && log.finishedPartNumber === finishedPn);
            accum = taskLogs.reduce((sum, log) => sum + (log.capacityPerDay || 0), 0);

            if (!total) total = accum > 0 ? accum * 1.5 : 1000;
            return { total, accum };
        };

        // ==========================================
        // 📊 核心仪表盘：织造 & 共挤产线卡片
        // ==========================================

        // 车间颜色映射：为每个 distinct workshopId 分配唯一颜色
        const workshopColorMap = computed(() => {
            const colors = ['#0284c7', '#ca8a04', '#059669', '#dc2626', '#7c3aed', '#db2777'];
            const map = new Map();
            let idx = 0;
            [...machineList.value, ...lineList.value].forEach(item => {
                const ws = item.workshopId;
                if (ws && !map.has(ws)) {
                    map.set(ws, colors[idx % colors.length]);
                    idx++;
                }
            });
            return map;
        });

        const factoryMachines = computed(() => {
            return machineList.value.map(m => {
                const logs = weavingLogsByMachine.value[cleanId(m.machineId)] || [];
                const latest = logs[0] || {};

                const realStatus = machineStatusMap.value[cleanId(m.machineId)];
                const currentTape = realStatus === '在产' ? (latest.tapePartNumber || '无任务') : '无任务';

                let orderId = '无订单';
                let accum = 0; let total = 0;

                if (currentTape !== '无任务') {
                    const progress = getWeavingOrderProgress(currentTape, m.machineId);
                    orderId = progress.orderId;
                    accum = progress.accum;
                    total = progress.total;
                }

                return {
                    ...m,
                    machineStatus: realStatus,
                    currentTape: currentTape,
                    currentOrder: orderId,
                    accum: accum,
                    total: total,
                    operator: realStatus === '在产' ? (latest.operatorName || m.operatorName || '未知') : '未知',
                    statusClass: getStatusClass(realStatus),
                    workshopColor: workshopColorMap.value.get(m.workshopId) || '#94a3b8'
                };
            });
        });

        const allWeavingMachines = computed(() => {
            return factoryMachines.value.sort((a, b) => parseInt(cleanId(a.machineId)) - parseInt(cleanId(b.machineId)));
        });

        const factoryLines = computed(() => {
            return lineList.value.map(l => {
                const logs = coexLogsByLine.value[cleanId(l.lineId)] || [];
                const latest = logs[0] || {};

                const realStatus = lineStatusMap.value[cleanId(l.lineId)];
                let orderId = '无任务';
                let finishedPn = '无任务';
                let accum = 0; let total = 0;

                if (realStatus === '在产' && latest.orderNumber && latest.finishedPartNumber) {
                    orderId = latest.orderNumber;
                    finishedPn = latest.finishedPartNumber;
                    const progress = getCoexOrderProgress(orderId, finishedPn, l.lineId);
                    accum = progress.accum;
                    total = progress.total;
                }

                return {
                    ...l,
                    lineStatus: realStatus,
                    currentOrder: orderId,
                    currentFinished: finishedPn,
                    accum: accum,
                    total: total,
                    currentSpeed: realStatus === '在产' ? (latest.productionSpeed || 0) : 0,
                    statusClass: getStatusClass(realStatus),
                    workshopColor: workshopColorMap.value.get(l.workshopId) || '#94a3b8'
                };
            }).sort((a, b) => parseInt(cleanId(a.lineId)) - parseInt(cleanId(b.lineId)));
        });

        // ==========================================
        // 📈 实时订单执行仪表看板测算引擎 (订单甘特图)
        // ==========================================
        const dashboardViewDays = ref('auto');

        const dashboardRows = computed(() => {
            const now = executionCurrentTime.value;
            const rows = [];

            machineList.value.forEach(m => {
                const logs = weavingLogsByMachine.value[cleanId(m.machineId)] || [];
                const latest = logs[0];

                const realStatus = machineStatusMap.value[cleanId(m.machineId)];

                if (latest && realStatus === '在产') {
                    const tapePn = latest.tapePartNumber || '未知';
                    const progress = getWeavingOrderProgress(tapePn, m.machineId);

                    const capPerDay = latest.capacityPerDay > 0 ? latest.capacityPerDay : 1500;
                    const pastMs = (progress.accum / capPerDay) * 24 * 3600 * 1000;
                    const remaining = Math.max(0, progress.total - progress.accum);
                    const futureMs = (remaining / capPerDay) * 24 * 3600 * 1000;

                    rows.push({
                        id: 'W_' + m.machineId, label: `织造 ${m.machineId}#`, type: 'weaving',
                        workshopId: m.workshopId, workshopColor: workshopColorMap.value.get(m.workshopId) || '#94a3b8',
                        task: {
                            partNumber: tapePn,
                            orderId: progress.orderId,
                            linkedFinished: progress.finishedPn,
                            accum: progress.accum,
                            total: progress.total,
                            start: now - pastMs,
                            end: now + futureMs,
                            capPerDay
                        }
                    });
                } else {
                    rows.push({ id: 'W_' + m.machineId, label: `织造 ${m.machineId}#`, type: 'weaving', workshopId: m.workshopId, workshopColor: workshopColorMap.value.get(m.workshopId) || '#94a3b8', task: null });
                }
            });

            lineList.value.forEach(l => {
                const logs = coexLogsByLine.value[cleanId(l.lineId)] || [];
                const latest = logs[0];

                const realStatus = lineStatusMap.value[cleanId(l.lineId)];

                if (latest && realStatus === '在产') {
                    const finishedPn = latest.finishedPartNumber || '未知';
                    const orderId = latest.orderNumber || '未知';
                    const progress = getCoexOrderProgress(orderId, finishedPn, l.lineId);

                    const capPerDay = latest.capacityPerDay > 0 ? latest.capacityPerDay : 2000;
                    const pastMs = (progress.accum / capPerDay) * 24 * 3600 * 1000;
                    const remaining = Math.max(0, progress.total - progress.accum);
                    const futureMs = (remaining / capPerDay) * 24 * 3600 * 1000;

                    rows.push({
                        id: 'C_' + l.lineId, label: `共挤 ${l.lineId}#`, type: 'coex',
                        workshopId: l.workshopId, workshopColor: workshopColorMap.value.get(l.workshopId) || '#94a3b8',
                        task: { partNumber: finishedPn, orderId, accum: progress.accum, total: progress.total, start: now - pastMs, end: now + futureMs, capPerDay }
                    });
                } else {
                    rows.push({ id: 'C_' + l.lineId, label: `共挤 ${l.lineId}#`, type: 'coex', workshopId: l.workshopId, workshopColor: workshopColorMap.value.get(l.workshopId) || '#94a3b8', task: null });
                }
            });
            return rows;
        });

        const dashboardBounds = computed(() => {
            if (dashboardViewDays.value === 'auto') {
                let minTime = executionCurrentTime.value - (3 * 24 * 3600 * 1000);
                let maxTime = executionCurrentTime.value + (3 * 24 * 3600 * 1000);
                dashboardRows.value.forEach(r => {
                    if (r.task) {
                        if (r.task.start < minTime) minTime = r.task.start;
                        if (r.task.end > maxTime) maxTime = r.task.end;
                    }
                });
                const span = maxTime - minTime;
                minTime -= span * 0.05; maxTime += span * 0.05;
                return { minTime, maxTime, span: maxTime - minTime };
            } else {
                let minTime = executionCurrentTime.value - (dashboardViewDays.value * 24 * 3600 * 1000);
                let maxTime = executionCurrentTime.value + (dashboardViewDays.value * 24 * 3600 * 1000);
                return { minTime, maxTime, span: maxTime - minTime };
            }
        });

        const dashboardTimeline = computed(() => {
            const { minTime, maxTime, span } = dashboardBounds.value;
            const markers = [];
            const days = span / (1000 * 60 * 60 * 24);
            let step = Math.ceil(days / 10);
            for (let t = minTime; t <= maxTime; t += step * 24 * 3600 * 1000) {
                const dateObj = new Date(t);
                markers.push({ left: ((t - minTime) / span * 100) + '%', label: `${dateObj.getMonth() + 1}/${dateObj.getDate()}` });
            }
            return markers;
        });

        const currentLineX = computed(() => {
            const { minTime, span } = dashboardBounds.value;
            return ((executionCurrentTime.value - minTime) / span * 100) + '%';
        });

        const dashboardRowsWithPos = computed(() => {
            const { minTime, maxTime, span } = dashboardBounds.value;
            return dashboardRows.value.map(r => {
                if (r.task) {
                    if (r.task.start >= maxTime || r.task.end <= minTime) {
                        r.task.outOfBounds = true;
                    } else {
                        r.task.outOfBounds = false;
                        const s = Math.max(minTime, r.task.start);
                        const e = Math.min(maxTime, r.task.end);
                        r.task.left = ((s - minTime) / span * 100) + '%';
                        r.task.width = ((e - s) / span * 100) + '%';

                        const current = executionCurrentTime.value;
                        if (current >= r.task.end) r.task.pastPct = '100%';
                        else if (current <= r.task.start) r.task.pastPct = '0%';
                        else {
                            let p = (current - s) / (e - s) * 100;
                            if (p > 100) p = 100;
                            if (p < 0) p = 0;
                            r.task.pastPct = p + '%';
                        }
                    }
                }
                return r;
            });
        });

        // ==========================================
        // 📅 订单交期全景甘特图大盘引擎
        // ==========================================
        const orderViewDays = ref('auto'); // 订单甘特图的缩放级别

        // 组装订单横排数据
        const allSchedulePlans = ref([]);
        const loadAllSchedulePlans = async () => {
            try {
                const res = await axios.get('/api/v1/workshops/estimation/schedule-summary', { skipErrorHandler: true });
                if (Array.isArray(res.data)) {
                    allSchedulePlans.value = res.data;
                }
            } catch (e) { ElMessage.error(errMsg(e)); }
        };

        const dashboardRowsWithPlanOverlay = computed(() => {
            return dashboardRowsWithPos.value.map(r => {
                let planInfo = null;
                if (r.task) {
                    const orderId = r.task.orderId;
                    const plan = allSchedulePlans.value.find(p => p.orderId === orderId);
                    if (plan && plan.plannedStartDate && plan.plannedEndDate) {
                        const planStart = new Date(plan.plannedStartDate).getTime();
                        const planEnd = new Date(plan.plannedEndDate).getTime();
                        const { minTime, span } = dashboardBounds.value;
                        const ps = Math.max(minTime, planStart);
                        const pe = Math.min(minTime + span, planEnd);
                        planInfo = {
                            left: ((ps - minTime) / span * 100) + '%',
                            width: ((pe - ps) / span * 100) + '%',
                            plannedStart: plan.plannedStartDate,
                            plannedEnd: plan.plannedEndDate,
                            orderId: orderId
                        };
                    }
                }
                return { ...r, planInfo };
            });
        });

        const applyManualAdjustments = async () => {
            if (!estResult.value || !estResult.value.details) return;
            loading.value = true;
            try {
                const adjustmentsMap = new Map();
                estResult.value.details.forEach(d => {
                    const hasAdj = d.manualWeavingCap != null || d.manualCoexCap != null
                        || d.manualWeavingMachineCount != null || d.manualCoexLineCount != null
                        || d.plannedMachine || d.plannedLine;
                    if (!hasAdj) return;
                    const pn = d.finishedPartNumber;
                    if (!adjustmentsMap.has(pn)) {
                        adjustmentsMap.set(pn, {
                            finishedPartNumber: pn,
                            manualWeavingCapacity: undefined,
                            manualCoexCapacity: undefined,
                            manualWeavingMachineCount: undefined,
                            manualCoexLineCount: undefined,
                            assignedMachineIds: undefined,
                            assignedLineIds: undefined
                        });
                    }
                    const adj = adjustmentsMap.get(pn);
                    // 织造行提供织造参数，共挤行提供共挤参数
                    if (d.allocationType === 'weaving') {
                        if (d.manualWeavingCap) adj.manualWeavingCapacity = d.manualWeavingCap;
                        if (d.manualWeavingMachineCount) adj.manualWeavingMachineCount = d.manualWeavingMachineCount;
                        if (d.plannedMachine) {
                            adj.assignedMachineIds = adj.assignedMachineIds || [];
                            adj.assignedMachineIds.push(d.plannedMachine);
                        }
                    } else if (d.allocationType === 'coex') {
                        if (d.manualCoexCap) adj.manualCoexCapacity = d.manualCoexCap;
                        if (d.manualCoexLineCount) adj.manualCoexLineCount = d.manualCoexLineCount;
                        if (d.plannedLine) {
                            adj.assignedLineIds = adj.assignedLineIds || [];
                            adj.assignedLineIds.push(d.plannedLine);
                        }
                    }
                });
                const adjustments = Array.from(adjustmentsMap.values());
                
                const reqBody = {
                    orderId: estResult.value.orderId,
                    globalBufferDays: estForm.globalBufferDays,
                    weavingReserveDays: estForm.weavingReserveDays || 0,
                    itemAdjustments: adjustments.length > 0 ? adjustments : undefined
                };
                
                const res = await axios.post('/api/v1/workshops/estimation/preview', reqBody, { skipErrorHandler: true });
                estResult.value = res.data;
                ElMessage.success('已根据人工调整参数重新推演！');
            } catch (error) {
                const msg = error.response?.data?.message || error.response?.data || '';
                ElMessage.error(typeof msg === 'string' && msg ? msg : '重新推演失败');
            } finally {
                loading.value = false;
            }
        };

        const orderGanttRows = computed(() => {
            const rows = [];
            orderList.value.forEach(o => {
                // 如果没有日期数据则跳过渲染
                if (!o.orderDate || !o.deliveryDate) return;

                const start = new Date(o.orderDate + 'T00:00:00').getTime();
                const end = new Date(o.deliveryDate + 'T23:59:59').getTime();

                let total = 0;
                let unfinished = 0;

                // 汇总该订单下所有零件的数量和未完米数
                if (o.items && o.items.length > 0) {
                    o.items.forEach(it => {
                        const len = it.totalLength || (it.metersPerRoll * it.rollCount) || 0;
                        total += len;
                        unfinished += it.unfinishedMeters != null ? it.unfinishedMeters : len;
                    });
                }

                const finished = Math.max(0, total - unfinished);
                const progress = total > 0 ? (finished / total * 100) : 0;

                const scheduleData = scheduleSummaryMap.value[o.orderId];
                rows.push({
                    id: o.orderId,
                    label: o.orderId,
                    customer: o.customerName || '未知',
                    sales: o.salesperson || '未知',
                    task: {
                        start, end, total, finished, unfinished, progress,
                        items: o.items || [],
                        plannedStart: scheduleData ? new Date(scheduleData.plannedStartDate).getTime() : null,
                        plannedEnd: scheduleData ? new Date(scheduleData.plannedEndDate).getTime() : null,
                        hasPlannedSchedule: !!scheduleData
                    }
                });
            });
            // 默认按交货期从早到晚排序
            return rows.sort((a, b) => a.task.end - b.task.end);
        });

        // 计算订单时间轴的边界
        const orderGanttBounds = computed(() => {
            const now = executionCurrentTime.value;
            if (orderViewDays.value === 'auto') {
                let minTime = now - (15 * 24 * 3600 * 1000);
                let maxTime = now + (45 * 24 * 3600 * 1000);
                if (orderGanttRows.value.length > 0) {
                    minTime = Math.min(...orderGanttRows.value.map(r => r.task.start));
                    maxTime = Math.max(...orderGanttRows.value.map(r => r.task.end));
                }
                const span = maxTime - minTime || (30 * 24 * 3600 * 1000);
                minTime -= span * 0.05; maxTime += span * 0.05;
                return { minTime, maxTime, span: maxTime - minTime };
            } else {
                let minTime = now - (orderViewDays.value * 24 * 3600 * 1000);
                let maxTime = now + (orderViewDays.value * 24 * 3600 * 1000);
                return { minTime, maxTime, span: maxTime - minTime };
            }
        });

        // 渲染订单顶部日期刻度
        const orderGanttTimeline = computed(() => {
            const { minTime, maxTime, span } = orderGanttBounds.value;
            const markers = [];
            const days = span / (1000 * 60 * 60 * 24);
            let step = Math.ceil(days / 10);
            for (let t = minTime; t <= maxTime; t += step * 24 * 3600 * 1000) {
                const dateObj = new Date(t);
                markers.push({ left: ((t - minTime) / span * 100) + '%', label: `${dateObj.getMonth() + 1}/${dateObj.getDate()}` });
            }
            return markers;
        });

        const orderGanttCurrentLineX = computed(() => {
            const { minTime, span } = orderGanttBounds.value;
            return ((executionCurrentTime.value - minTime) / span * 100) + '%';
        });

        // 智能裁剪界外订单任务
        const orderGanttRowsWithPos = computed(() => {
            const { minTime, maxTime, span } = orderGanttBounds.value;
            return orderGanttRows.value.map(r => {
                if (r.task.start >= maxTime || r.task.end <= minTime) {
                    r.task.outOfBounds = true;
                } else {
                    r.task.outOfBounds = false;
                    const s = Math.max(minTime, r.task.start);
                    const e = Math.min(maxTime, r.task.end);
                    r.task.left = ((s - minTime) / span * 100) + '%';
                    r.task.width = ((e - s) / span * 100) + '%';
                    r.task.pastPct = r.task.progress + '%';
                    if (r.task.hasPlannedSchedule) {
                        const ps = Math.max(minTime, r.task.plannedStart);
                        const pe = Math.min(maxTime, r.task.plannedEnd);
                        r.task.plannedLeft = ((ps - minTime) / span * 100) + '%';
                        r.task.plannedWidth = ((pe - ps) / span * 100) + '%';
                    }
                }
                return r;
            });
        });

        // ==========================================
        // 🔍 订单甘特图下钻：详细排产甘特图弹窗
        // ==========================================
        const drillDownDialogVisible = ref(false);
        const drillDownOrderId = ref('');
        const drillDownDetail = ref(null);
        const drillDownLoading = ref(false);

        // 🌟 性能优化：提取共享 drillDownGanttBounds
        const drillDownGanttBounds = computed(() => {
            if (!drillDownDetail.value || !drillDownDetail.value.details) return null;
            let minTime = new Date('2099-01-01').getTime();
            let maxTime = new Date('2000-01-01').getTime();
            drillDownDetail.value.details.forEach(d => {
                if (d.weavingStart) minTime = Math.min(minTime, new Date(d.weavingStart).getTime());
                if (d.weavingEnd) maxTime = Math.max(maxTime, new Date(d.weavingEnd).getTime());
                if (d.coexStart) minTime = Math.min(minTime, new Date(d.coexStart).getTime());
                if (d.coexEnd) maxTime = Math.max(maxTime, new Date(d.coexEnd).getTime());
            });
            const span = maxTime - minTime;
            minTime -= span * 0.05; maxTime += span * 0.05;
            return { minTime, maxTime, span: maxTime - minTime };
        });

        const drillDownGanttTimeline = computed(() => {
            if (!drillDownGanttBounds.value) return [];
            const { minTime, maxTime, span } = drillDownGanttBounds.value;
            const markers = [];
            const days = span / (1000 * 60 * 60 * 24);
            let step = 1;
            if (days > 15) step = Math.ceil(days / 10);
            for (let t = minTime; t <= maxTime; t += step * 24 * 3600 * 1000) {
                const dateObj = new Date(t);
                markers.push({ left: ((t - minTime) / span * 100) + '%', label: `${dateObj.getMonth() + 1}月${dateObj.getDate()}日` });
            }
            return markers;
        });

        const drillDownGanttRows = computed(() => {
            if (!drillDownGanttBounds.value || !drillDownDetail.value || !drillDownDetail.value.details) return [];
            const { minTime, span: totalSpan } = drillDownGanttBounds.value;
            const rowsMap = new Map();
            const colors = ['#3b82f6', '#8b5cf6', '#f59e0b', '#10b981', '#ec4899', '#f43f5e'];
            drillDownDetail.value.details.forEach((d, idx) => {
                const color = colors[idx % colors.length];
                if (d.weavingStart) {
                    const rowKey = 'W_' + d.tapePartNumber;
                    if (!rowsMap.has(rowKey)) {
                        rowsMap.set(rowKey, { id: rowKey, label: '🧶 ' + d.tapePartNumber, type: 'weaving', tasks: [] });
                    }
                    const s = new Date(d.weavingStart).getTime();
                    const e = new Date(d.weavingEnd).getTime();
                    rowsMap.get(rowKey).tasks.push({
                        ...d, rawStart: d.weavingStart.replace('T',' '), rawEnd: d.weavingEnd.replace('T',' '),
                        left: ((s - minTime) / totalSpan * 100) + '%', width: ((e - s) / totalSpan * 100) + '%',
                        color: color, label: `带坯:${d.tapePartNumber} (${(d.tapeMetersNeed || 0).toFixed(0)}m)`,
                        typeStr: '织造排期'
                    });
                }
                if (d.coexStart) {
                    const rowKey = 'C_' + d.finishedPartNumber;
                    if (!rowsMap.has(rowKey)) {
                        rowsMap.set(rowKey, { id: rowKey, label: '🗜️ ' + d.finishedPartNumber, type: 'coex', tasks: [] });
                    }
                    const s = new Date(d.coexStart).getTime();
                    const e = new Date(d.coexEnd).getTime();
                    rowsMap.get(rowKey).tasks.push({
                        ...d, rawStart: d.coexStart.replace('T',' '), rawEnd: d.coexEnd.replace('T',' '),
                        left: ((s - minTime) / totalSpan * 100) + '%', width: ((e - s) / totalSpan * 100) + '%',
                        color: color, label: `成品:${d.finishedPartNumber} (${(d.finishedMeters || 0).toFixed(0)}m)`,
                        typeStr: '共挤排期',
                        events: buildTapeChangeMarkers(d, s, e)
                    });
                }
            });
            return Array.from(rowsMap.values());
        });

        const openOrderDetail = async (orderId) => {
            drillDownOrderId.value = orderId;
            drillDownLoading.value = true;
            drillDownDialogVisible.value = true;
            drillDownDetail.value = null;
            try {
                const res = await axios.post('/api/v1/workshops/estimation/preview', { orderId: orderId }, { skipErrorHandler: true });
                drillDownDetail.value = res.data;
            } catch (error) {
                const msg = error.response?.data?.message || error.response?.data || '';
                const msgStr = typeof msg === 'string' ? msg : '';
                if (msgStr.startsWith('MISSING_PROCESS:')) {
                    ElMessage.warning('该订单缺少工艺路线定义，请先配置工艺参数');
                } else if (msgStr.startsWith('MISSING_CAPACITY:')) {
                    ElMessage.warning('该订单缺少产能数据，请先录入历史产能');
                } else if (msgStr) {
                    ElMessage.error(msgStr);
                } else {
                    ElMessage.error('获取排产详情失败');
                }
            } finally {
                drillDownLoading.value = false;
            }
        };

        // 🌟 防抖工具函数
        let inquiryDebounceTimer = null;
        const debouncedFetchInquiry = () => {
            clearTimeout(inquiryDebounceTimer);
            inquiryDebounceTimer = setTimeout(() => fetchInquiry(), 500);
        };

        let adjustDebounceTimer = null;
        const debouncedApplyAdjustments = () => {
            clearTimeout(adjustDebounceTimer);
            adjustDebounceTimer = setTimeout(() => applyManualAdjustments(), 500);
        };

        // 🌟 将新页面加入到路由刷新监听器中
        const loadedPages = ref(new Set());
        watch(activeMenu, (newVal) => {
            if (loadedPages.value.has(newVal)) return;
            loadedPages.value.add(newVal);
            if (newVal === 'dashboard') { loadMachinesAndLines(); loadWeavingLogs(); loadCoexLogs(); loadInventory(); }
            if (newVal === 'inventory') loadInventory(); 
            if (newVal === 'weaving') { loadWeavingPage(); loadProcesses(true); }
            if (newVal === 'coex') { loadCoexPage(); loadProcesses(true); } 
            if (newVal === 'order') loadOrders();
            if (newVal === 'process') { loadProcesses(); loadProcessPage(); }
            if (newVal === 'machine-archive' || newVal === 'line-archive') { loadMachinesAndLines(); }
            if (newVal === 'execution') { loadWeavingLogs(); loadCoexLogs(); loadOrders(); loadAllSchedulePlans(); }
            if (newVal === 'order-dashboard') { loadOrders(); loadScheduleSummary(); }
            if (newVal === 'inquiry') { /* 询单页面按需加载 */ }
        });

        // ==========================================
        // 🌟 全表全列客户端筛选 filter options
        // ==========================================
        // 织造台账（~20列）
        const wF = (p) => colFilterOpts(paginatedWeavingLogs, p);
        const weavingEntryDateFilterOpts = computed(() => wF('entryDate'));
        const weavingPartNumberFilterOpts = computed(() => wF('partNumber'));
        const weavingTapeCodeFilterOpts = computed(() => wF('tapeCode'));
        const weavingModelSpecFilterOpts = computed(() => wF('modelSpec'));
        const weavingWarpThreadFilterOpts = computed(() => wF('warpThread'));
        const weavingWeftThreadFilterOpts = computed(() => wF('weftThread'));
        const weavingDataQualityFilterOpts = computed(() => wF('dataQualityFlag'));
        const weavingWorkerNameFilterOpts = computed(() => wF('workerName'));
        const weavingShiftOutputFilterOpts = computed(() => wF('shiftOutput'));
        // 共挤台账（~12列）
        const cF = (p) => colFilterOpts(paginatedCoexLogs, p);
        const coexLogDateFilterOpts = computed(() => cF('logDate'));
        const coexProductTypeFilterOpts = computed(() => cF('productType'));
        const coexMainMaterialFilterOpts = computed(() => cF('mainMaterial'));
        const coexWeightKgFilterOpts = computed(() => cF('weightKg'));
        const coexCapacityMetersFilterOpts = computed(() => cF('capacityMeters'));
        const coexLeakageKgFilterOpts = computed(() => cF('leakageKg'));
        const coexSourceYearFilterOpts = computed(() => cF('sourceFileYear'));
        const coexDataQualityFilterOpts = computed(() => cF('dataQualityFlag'));
        // 库存表
        const iF = (p) => colFilterOpts(paginatedInventoryList, p);
        const invPartNumberFilterOpts = computed(() => iF('partNumber'));
        const invModelSpecFilterOpts = computed(() => iF('modelSpec'));
        const invSnapshotDateFilterOpts = computed(() => iF('snapshotDate'));
        // 工艺路线表
        const pF = (p) => colFilterOpts(paginatedProcessList, p);
        const procFinishedPnFilterOpts = computed(() => pF('finishedPartNumber'));
        const procTapePnFilterOpts = computed(() => pF('tapePartNumber'));
        const procFinishedSpecFilterOpts = computed(() => pF('finishedModelSpec'));
        const procTapeSpecFilterOpts = computed(() => pF('tapeModelSpec'));
        // 订单表
        const oF = (p) => colFilterOpts(paginatedOrderList, p);
        const orderIdFilterOpts = computed(() => oF('orderId'));
        const orderCustomerFilterOpts = computed(() => oF('customerName'));
        const orderSalespersonFilterOpts = computed(() => oF('salesperson'));
        const orderOrderDateFilterOpts = computed(() => oF('orderDate'));
        const orderDeliveryDateFilterOpts = computed(() => oF('deliveryDate'));
        // 机台档案表
        const mF = (p) => colFilterOpts(filteredMachineArchive, p);
        const machineIdFilterOpts = computed(() => mF('machineId'));
        const machineCaliberMinFilterOpts = computed(() => mF('caliberMin'));
        const machineCaliberMaxFilterOpts = computed(() => mF('caliberMax'));
        const machineWorkshopFilterOpts = computed(() => mF('workshopId'));
        const machineStatusFilterOpts = computed(() => mF('machineStatus'));
        const machineOperatorFilterOpts = computed(() => mF('operatorName'));
        // 产线档案表
        const lF = (p) => colFilterOpts(filteredLineArchive, p);
        const lineIdFilterOpts = computed(() => lF('lineId'));
        const lineCaliberMinFilterOpts = computed(() => lF('caliberMin'));
        const lineCaliberMaxFilterOpts = computed(() => lF('caliberMax'));
        const lineWorkshopFilterOpts = computed(() => lF('workshopId'));
        const lineStatusFilterOpts = computed(() => lF('lineStatus'));

        return {
            isLoggedIn, currentUser, activeMenu, loading, loginForm, handleLogin, handleLogout, handleMenuSelect, refreshCurrentPage, machineList, lineList,
            weavingForm, weavingLogList, submitWeaving, openEditWeaving, deleteWeaving, resetWeavingForm, weavingFileRef, exportWeavingExcel, handleWeavingImport, weavingKeyword, weavingMachineFilterOptions, weavingShiftFilterOptions, weavingPage, weavingPageSize, paginatedWeavingLogs, weavingTotal, loadWeavingPage, searchWeaving, handleWeavingFilterChange, onWeavingSizeChange, onWeavingPageChange, recheckGradeB,
            clientColumnFilter, weavingEntryDateFilterOpts, weavingPartNumberFilterOpts, weavingTapeCodeFilterOpts, weavingModelSpecFilterOpts, weavingWarpThreadFilterOpts, weavingWeftThreadFilterOpts, weavingDataQualityFilterOpts, weavingWorkerNameFilterOpts, weavingShiftOutputFilterOpts,
            coexForm, coexLogList, submitCoex, openEditCoex, deleteCoex, resetCoexForm, coexFileRef, exportCoexExcel, handleCoexImport, coexKeyword, coexMachineFilterOptions, coexModelFilterOptions, coexColorFilterOptions, coexPage, coexPageSize, paginatedCoexLogs, coexTotal, loadCoexPage, searchCoex, handleCoexFilterChange, onCoexSizeChange, onCoexPageChange, coexImportYear,
            coexLogDateFilterOpts, coexProductTypeFilterOpts, coexMainMaterialFilterOpts, coexWeightKgFilterOpts, coexCapacityMetersFilterOpts, coexLeakageKgFilterOpts, coexSourceYearFilterOpts, coexDataQualityFilterOpts,
            invSearchKeyword, inventoryList, invLoading, invDialogVisible, invSaveLoading, invForm, loadInventory, openAddInv, openEditInv, saveInv, deleteInv, invPage, invPageSize, paginatedInventoryList, invTotal, invStockTypeFilterOptions, searchInventory, handleInvFilterChange, onInvSizeChange, onInvPageChange,
            invPartNumberFilterOpts, invModelSpecFilterOpts, invSnapshotDateFilterOpts,
            splitDialogVisible, splitSaving, splitSource, splitLengths, splitTotal, openSplitInv, addSplitRow, removeSplitRow, submitSplit,
            dailySummaryVisible, dailySummaryLoading, dailySummaryRange, dailySummaryData, loadDailySummary, openDailySummary,
            importResult, lastImportSource, importLoading, inventorySnapshotDate, inventoryFile, handleInventoryFileChange, importInventory, exportInventory, reconciliationData, reconciliationLoading, loadReconciliationReport, confirmReconciliation,
            orderHeader, orderItems, isOrderEditMode, orderList, calcTotal, addOrderItem, removeOrderItem, submitOrder, resetOrderForm, editOrder, deleteOrder, orderKeyword, orderPage, orderPageSize, paginatedOrderList, orderTotal, orderPartFilterOptions, searchOrders, handleOrderFilterChange, onOrderSizeChange, onOrderPageChange,
            orderIdFilterOpts, orderCustomerFilterOpts, orderSalespersonFilterOpts, orderOrderDateFilterOpts, orderDeliveryDateFilterOpts,
            estForm, estResult, fetchInitialDraft, commitFinalScheduleToDb, ganttRows, ganttTimeline, capacityDialogVisible, capacityPrompt, manualCap, submitManualCapacity, capacityFieldLabel, weavingScheduleDetails, coexScheduleDetails, rowCandidateMachines, rowCandidateLines, estMaterialSummary,
            inquiryForm, inquiryResult, addInquiryItem, removeInquiryItem, fetchInquiry, inquiryGanttTimeline, inquiryGanttRows, inquiryMaterialSummary,
            inquiryResCounts, debouncedInquiryRecalc, onInquiryAssignChange,
            processList, processDialogVisible, processForm, openAddProcess, openEditProcess, saveProcess, deleteProcess, loadProcesses, processFileRef, exportProcessExcel, handleProcessImport, processKeyword, processPage, processPageSize, paginatedProcessList, processTotal, processMaterialFilterOptions, loadProcessPage, searchProcess, handleProcessFilterChange, onProcessSizeChange, onProcessPageChange,
            procFinishedPnFilterOpts, procTapePnFilterOpts, procFinishedSpecFilterOpts, procTapeSpecFilterOpts,
            caliberLabel,
            machineArchiveKeyword, machineArchivePage, machineArchivePageSize, machineArchiveTotal, paginatedMachineArchiveList, machineDialogVisible, machineArchiveEditMode, machineForm, machineFileRef, searchMachineArchive, openAddMachineArchive, openEditMachineArchive, saveMachineArchive, deleteMachineArchive, handleMachineArchiveImport, exportMachineArchiveExcel,
            machineIdFilterOpts, machineCaliberMinFilterOpts, machineCaliberMaxFilterOpts, machineWorkshopFilterOpts, machineStatusFilterOpts, machineOperatorFilterOpts,
            lineArchiveKeyword, lineArchivePage, lineArchivePageSize, lineArchiveTotal, paginatedLineArchiveList, lineDialogVisible, lineArchiveEditMode, lineForm, lineFileRef, searchLineArchive, openAddLineArchive, openEditLineArchive, saveLineArchive, deleteLineArchive, handleLineArchiveImport, exportLineArchiveExcel,
            lineIdFilterOpts, lineCaliberMinFilterOpts, lineCaliberMaxFilterOpts, lineWorkshopFilterOpts, lineStatusFilterOpts,
            allWeavingMachines, factoryLines, workshopColorMap,
            executionCurrentTime, dashboardTimeline, currentLineX, dashboardRowsWithPos, dashboardViewDays,
            orderFileRef, exportOrderExcel, handleOrderImport, orderViewDays, orderGanttRowsWithPos, orderGanttTimeline, orderGanttCurrentLineX,
            multiOrderMode, selectedOrderIds, fetchMultiOrderSchedule, multiOrderResult,
            scheduleSummaryMap, loadScheduleSummary, orderSuggestions,
            drillDownDialogVisible, drillDownOrderId, drillDownDetail, drillDownLoading,
            drillDownGanttTimeline, drillDownGanttRows, openOrderDetail,
            allSchedulePlans, loadAllSchedulePlans, dashboardRowsWithPlanOverlay,
            applyManualAdjustments,
            debouncedApplyAdjustments, debouncedFetchInquiry
        };
    }
});
app.use(ElementPlus); app.mount('#app');