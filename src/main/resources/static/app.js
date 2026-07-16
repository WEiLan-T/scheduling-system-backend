const { createApp, ref, reactive, onMounted, watch, computed } = Vue;
const { ElMessage, ElMessageBox } = ElementPlus;

const app = createApp({
    setup() {
        const getToday = () => {
            const d = new Date();
            return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        };

        const isLoggedIn = ref(false);
        const currentUser = ref('');
        const activeMenu = ref('dashboard'); // 默认登入后展示全厂全景仪表
        const loading = ref(false);

        const machineList = ref([]);
        const lineList = ref([]);

        // ================= AXIOS 拦截器 =================
        axios.interceptors.request.use(config => {
            const token = localStorage.getItem('jwt_token');
            if (token) config.headers['Authorization'] = 'Bearer ' + token;
            return config;
        });

        axios.interceptors.response.use(response => response, error => {
            if (error.response && error.response.status === 403) ElMessage({ message: '⛔ 权限不足！', type: 'error' });
            else if (error.response && error.response.status === 401) { ElMessage.warning('凭证过期，请重新登入。'); handleLogout(); }
            else if (error.response && error.response.data) {
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
            } catch (e) {}
        };

        onMounted(() => {
            const token = localStorage.getItem('jwt_token');
            const user = localStorage.getItem('current_user');
            if (token && user) {
                isLoggedIn.value = true; currentUser.value = user;
                loadMachinesAndLines(); loadWeavingLogs(); loadCoexLogs(); loadInventory();
            }
        });

        const loginForm = reactive({ username: '', password: '' });
        const handleLogin = async () => {
            if (!loginForm.username || !loginForm.password) return;
            loading.value = true;
            try {
                const res = await axios.post('/api/v1/auth/login', loginForm);
                if (res.data && res.data.includes('eyJ')) {
                    localStorage.setItem('jwt_token', res.data.trim()); localStorage.setItem('current_user', loginForm.username);
                    isLoggedIn.value = true; currentUser.value = loginForm.username; ElMessage.success('核验通过！');
                    loadMachinesAndLines(); loadWeavingLogs(); loadCoexLogs(); loadInventory();
                }
            } catch (error) { if(error.response && error.response.status === 401) ElMessage.error('账号或密码错误！'); } finally { loading.value = false; }
        };
        const handleLogout = () => { localStorage.clear(); isLoggedIn.value = false; };
        const handleMenuSelect = (index) => { activeMenu.value = index; estResult.value = null; };

        const refreshCurrentPage = () => {
            if (activeMenu.value === 'dashboard') { loadMachinesAndLines(); loadWeavingLogs(); loadCoexLogs(); loadInventory(); ElMessage.success('🔄 厂区数字孪生快照已更新'); }
            else if (activeMenu.value === 'weaving') { loadWeavingLogs(); ElMessage.success('🔄 织造历史台账同步刷新完成'); }
            else if (activeMenu.value === 'process') { loadProcesses(); ElMessage.success('🔄 工艺路线参数配置库已同步刷新'); }
            else if (activeMenu.value === 'coex') { loadCoexLogs(); ElMessage.success('🔄 共挤历史台账同步刷新完成'); }
            else if (activeMenu.value === 'inventory') { loadInventory(); ElMessage.success('🔄 虚拟分批库存大盘已刷新'); }
            else if (activeMenu.value === 'order') { loadOrders(); ElMessage.success('🔄 销售合同档案订单库已刷新'); }
            else if (activeMenu.value === 'estimation') {
                if (estForm.orderId) { fetchInitialDraft(); } else { ElMessage.success('🔄 排产控制中心已就绪'); }
            }
        };

        // ==========================================
        // 🧶 织造车间 MES (含搜索与分页)
        // ==========================================
        const weavingLogList = ref([]); const weavingFileRef = ref(null);
        const weavingSearch = reactive({ tapePartNumber: '', tapeNumber: '', machineId: '' });
        const weavingPage = ref(1);
        const filteredWeavingLogs = computed(() => {
            return weavingLogList.value.filter(item => {
                return (!(weavingSearch.tapePartNumber) || (item.tapePartNumber || '').includes(weavingSearch.tapePartNumber)) &&
                    (!(weavingSearch.tapeNumber) || (item.tapeNumber || '').includes(weavingSearch.tapeNumber)) &&
                    (!(weavingSearch.machineId) || (item.machineId || '').includes(weavingSearch.machineId));
            });
        });
        const paginatedWeavingLogs = computed(() => {
            const start = (weavingPage.value - 1) * 10;
            return filteredWeavingLogs.value.slice(start, start + 10);
        });
        const weavingTotal = computed(() => filteredWeavingLogs.value.length);
        watch(weavingSearch, () => { weavingPage.value = 1; }, { deep: true });

        const weavingForm = reactive({
            id: null, entryDate: getToday(), machineId: '', tapePartNumber: '', tapeNumber: '',
            modelSpec: '', warpSpec: '', weftSpec: '', shift: '白', operatorName: '',
            capacityPerDay: 0, standardCapacity: 0, standardHours: 0, standardHourlyCapacity: 0,
            performanceHours: 0, isDataNormal: true, totalDemand: 0, remarks: '', workshopId: '织造车间'
        });

        const resetWeavingForm = () => {
            weavingForm.id = null; weavingForm.tapePartNumber = ''; weavingForm.tapeNumber = '';
            weavingForm.modelSpec = ''; weavingForm.warpSpec = ''; weavingForm.weftSpec = '';
            weavingForm.operatorName = ''; weavingForm.capacityPerDay = 0; weavingForm.standardCapacity = 0;
            weavingForm.standardHours = 0; weavingForm.standardHourlyCapacity = 0; weavingForm.performanceHours = 0;
            weavingForm.totalDemand = 0; weavingForm.isDataNormal = true; weavingForm.remarks = '';
        };

        watch(() => weavingForm.machineId, (newId) => {
            const machine = machineList.value.find(m => m.machineId === newId);
            if (machine) { weavingForm.workshopId = machine.workshopId; }
        });

        const loadWeavingLogs = async () => { try { const res = await axios.get('/api/v1/workshops/integration/weaving/logs/list'); weavingLogList.value = res.data; } catch (e) {} };
        const submitWeaving = async () => { loading.value = true; try { const res = await axios.post('/api/v1/workshops/integration/weaving/logs', weavingForm); ElMessage.success(res.data); loadWeavingLogs(); resetWeavingForm(); } catch (error) {} finally { loading.value = false; } };
        const openEditWeaving = (row) => { Object.assign(weavingForm, row); window.scrollTo({ top: 0, behavior: 'smooth' }); };
        const deleteWeaving = async (id) => { try { await ElMessageBox.confirm('撤销台账将自动回扣并同步冲减库存，是否继续？', '高危生产警告', { type: 'warning' }); const res = await axios.delete(`/api/v1/workshops/integration/weaving/logs/${id}`); ElMessage.success(res.data); loadWeavingLogs(); if (weavingForm.id === id) resetWeavingForm(); } catch (e) {} };

        const exportWeavingExcel = async () => { try { const res = await axios.get('/api/v1/workshops/integration/weaving/export', { responseType: 'blob' }); const blob = new Blob([res.data]); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '织造车间产能明细汇总.xlsx'; link.click(); ElMessage.success('📥 导出成功！'); } catch(e) { ElMessage.error('导出失败'); } };
        const handleWeavingImport = async (e) => { const file = e.target.files[0]; if (!file) return; const fd = new FormData(); fd.append('file', file); loading.value = true; try { const res = await axios.post('/api/v1/workshops/integration/weaving/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } }); ElMessage.success(res.data); loadWeavingLogs(); } catch(err) {} finally { loading.value = false; e.target.value = ''; } };

        // ==========================================
        // 🗜️ 共挤车间 MES (含搜索与分页)
        // ==========================================
        const coexLogList = ref([]); const coexFileRef = ref(null);
        const coexSearch = reactive({ orderNumber: '', lineId: '', finishedPartNumber: '', semiFinishedNumber: '', tapePartNumber: '' });
        const coexPage = ref(1);
        const filteredCoexLogs = computed(() => {
            return coexLogList.value.filter(item => {
                return (!(coexSearch.orderNumber) || (item.orderNumber || '').includes(coexSearch.orderNumber)) &&
                    (!(coexSearch.lineId) || (item.lineId || '').includes(coexSearch.lineId)) &&
                    (!(coexSearch.finishedPartNumber) || (item.finishedPartNumber || '').includes(coexSearch.finishedPartNumber)) &&
                    (!(coexSearch.semiFinishedNumber) || (item.semiFinishedNumber || '').includes(coexSearch.semiFinishedNumber)) &&
                    (!(coexSearch.tapePartNumber) || (item.tapePartNumber || '').includes(coexSearch.tapePartNumber));
            });
        });
        const paginatedCoexLogs = computed(() => {
            const start = (coexPage.value - 1) * 10;
            return filteredCoexLogs.value.slice(start, start + 10);
        });
        const coexTotal = computed(() => filteredCoexLogs.value.length);
        watch(coexSearch, () => { coexPage.value = 1; }, { deep: true });

        const coexForm = reactive({
            id: null, entryDate: getToday(), lineId: '', orderNumber: '',
            finishedPartNumber: '', semiFinishedNumber: '', finishedModelSpec: '',
            workshopId: '', caliberLimit: '', lineStatus: '在产', productionSpeed: 0,
            capacityPerDay: 0, isDataNormal: true, tapeDemandQty: 0, tapePartNumber: '', tapeNumber: '', remarks: ''
        });

        const resetCoexForm = () => {
            coexForm.id = null; coexForm.orderNumber = ''; coexForm.finishedPartNumber = ''; coexForm.semiFinishedNumber = '';
            coexForm.finishedModelSpec = ''; coexForm.tapeNumber = ''; coexForm.productionSpeed = 0; coexForm.capacityPerDay = 0;
            coexForm.tapeDemandQty = 0; coexForm.isDataNormal = true; coexForm.remarks = '';
        };

        watch(() => coexForm.lineId, (newId) => {
            const line = lineList.value.find(l => l.lineId === newId);
            if (line) { coexForm.workshopId = line.workshopId; coexForm.caliberLimit = line.caliberLimit; }
        });

        const loadCoexLogs = async () => { try { const res = await axios.get('/api/v1/workshops/integration/coextrusion/logs/list'); coexLogList.value = res.data; } catch (e) {} };
        const submitCoex = async () => { loading.value = true; try { const res = await axios.post('/api/v1/workshops/integration/coextrusion/logs', coexForm); ElMessage.success(res.data); loadCoexLogs(); resetCoexForm(); } catch (error) {} finally { loading.value = false; } };
        const openEditCoex = (row) => { Object.assign(coexForm, row); window.scrollTo({ top: 0, behavior: 'smooth' }); };
        const deleteCoex = async (id) => { try { await ElMessageBox.confirm('确认删除并退还库存？', '警告', { type: 'warning' }); const res = await axios.delete(`/api/v1/workshops/integration/coextrusion/logs/${id}`); ElMessage.success(res.data); loadCoexLogs(); if (coexForm.id === id) resetCoexForm(); } catch (e) {} };

        const exportCoexExcel = async () => { try { const res = await axios.get('/api/v1/workshops/integration/coextrusion/export', { responseType: 'blob' }); const blob = new Blob([res.data]); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '共挤车间历史台账汇总.xlsx'; link.click(); ElMessage.success('📥 导出成功！'); } catch(e) { ElMessage.error('导出失败'); } };
        const handleCoexImport = async (e) => { const file = e.target.files[0]; if (!file) return; const fd = new FormData(); fd.append('file', file); loading.value = true; try { const res = await axios.post('/api/v1/workshops/integration/coextrusion/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } }); ElMessage.success(res.data); loadCoexLogs(); } catch(err) {} finally { loading.value = false; e.target.value = ''; } };

        // ==========================================
        // 📦 虚拟库存总览
        // ==========================================
        const invSearchKeyword = ref(''); const inventoryList = ref([]); const invLoading = ref(false); const invDialogVisible = ref(false); const invSaveLoading = ref(false); const invForm = reactive({ id: null, entryDate: getToday(), tapePartNumber: '', tapeNumber: '', finishedPartNumber: '', currentStockMeters: 0 });

        const invPage = ref(1);
        const paginatedInventoryList = computed(() => {
            const start = (invPage.value - 1) * 10;
            return inventoryList.value.slice(start, start + 10);
        });
        const invTotal = computed(() => inventoryList.value.length);
        watch(inventoryList, () => { invPage.value = 1; });

        const loadInventory = async () => {
            invLoading.value = true;
            try {
                const res = await axios.get('/api/v1/workshops/integration/inventory/list', { params: { keyword: invSearchKeyword.value } });
                const groupMap = {};
                res.data.forEach(item => {
                    const tpn = item.tapePartNumber;
                    if (!groupMap[tpn]) { groupMap[tpn] = { tapePartNumber: tpn, finishedPartNumber: item.finishedPartNumber, totalStockMeters: 0, batches: [] }; }
                    groupMap[tpn].totalStockMeters += item.currentStockMeters; groupMap[tpn].batches.push(item);
                });
                inventoryList.value = Object.values(groupMap);
            } catch (error) {} finally { invLoading.value = false; }
        };
        const openAddInv = () => { Object.assign(invForm, { id: null, entryDate: getToday(), tapePartNumber: '', tapeNumber: '', finishedPartNumber: '', currentStockMeters: 0 }); invDialogVisible.value = true; };
        const openEditInv = (row) => { Object.assign(invForm, row); invDialogVisible.value = true; };
        const saveInv = async () => { if (!invForm.tapePartNumber) return; invSaveLoading.value = true; try { const res = await axios.post('/api/v1/workshops/integration/inventory/save', invForm); ElMessage.success(res.data); invDialogVisible.value = false; loadInventory(); } catch (error) {} finally { invSaveLoading.value = false; } };
        const deleteInv = async (id) => { try { await axios.delete(`/api/v1/workshops/integration/inventory/${id}`); loadInventory(); ElMessage.success('已删除');} catch (error) {} };

        // ==========================================
        // ⚙️ 工艺路线配置库
        // ==========================================
        const processList = ref([]); const processFileRef = ref(null); const processDialogVisible = ref(false);
        const processForm = reactive({ id: null, finishedPartNumber: '', tapePartNumber: '', warpSpec: '', weftSpec: '' });

        const processSearch = reactive({ finishedPartNumber: '', tapePartNumber: '' });
        const processPage = ref(1);
        const filteredProcessList = computed(() => {
            return processList.value.filter(item => {
                return (!(processSearch.finishedPartNumber) || (item.finishedPartNumber || '').includes(processSearch.finishedPartNumber)) &&
                    (!(processSearch.tapePartNumber) || (item.tapePartNumber || '').includes(processSearch.tapePartNumber));
            });
        });
        const paginatedProcessList = computed(() => {
            const start = (processPage.value - 1) * 10;
            return filteredProcessList.value.slice(start, start + 10);
        });
        const processTotal = computed(() => filteredProcessList.value.length);
        watch(processSearch, () => { processPage.value = 1; }, { deep: true });

        const loadProcesses = async () => { try { const res = await axios.get('/api/v1/workshops/integration/process/list'); processList.value = res.data; } catch (e) {} };
        const openAddProcess = () => { Object.assign(processForm, { id: null, finishedPartNumber: '', finishedModelSpec: '', tapePartNumber: '', tapeModelSpec: '', warpSpec: '', weftSpec: '' }); processDialogVisible.value = true; };
        const openEditProcess = (row) => { Object.assign(processForm, row); processDialogVisible.value = true; };
        const saveProcess = async () => { if (!processForm.finishedPartNumber || !processForm.tapePartNumber) { ElMessage.error('零件号不能为空！'); return; } try { const res = await axios.post('/api/v1/workshops/integration/process/save', processForm); ElMessage.success(res.data); processDialogVisible.value = false; loadProcesses(); } catch (e) {} };
        const deleteProcess = async (id) => { try { await ElMessageBox.confirm('确认解除？', '警告', { type: 'warning' }); const res = await axios.delete(`/api/v1/workshops/integration/process/${id}`); ElMessage.success(res.data); loadProcesses(); } catch (e) {} };

        const exportProcessExcel = async () => { try { const response = await axios.get('/api/v1/workshops/integration/process/export', { responseType: 'blob' }); const blob = new Blob([response.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '工艺路线数据大盘.xlsx'; link.click(); ElMessage.success('📥 导出成功！'); } catch (error) { ElMessage.error('导出失败，请检查服务器！'); } };
        const handleProcessImport = async (event) => { const file = event.target.files[0]; if (!file) return; const formData = new FormData(); formData.append('file', file); loading.value = true; try { const response = await axios.post('/api/v1/workshops/integration/process/import', formData, { headers: { 'Content-Type': 'multipart/form-data' } }); ElMessage.success(response.data); loadProcesses(); } catch (error) {} finally { loading.value = false; event.target.value = ''; } };

        // ==========================================
        // 🛒 销售订单核心
        // ==========================================
        const isOrderEditMode = ref(false); const orderList = ref([]); const simDialogVisible = ref(false); const simResult = ref(null);
        const orderHeader = reactive({ orderId: '', placerName: '', orderDate: getToday(), deliveryDate: '' });
        const orderItems = ref([{ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }]);

        const orderPage = ref(1);
        const paginatedOrderList = computed(() => {
            const start = (orderPage.value - 1) * 10;
            return orderList.value.slice(start, start + 10);
        });
        const orderTotal = computed(() => orderList.value.length);
        watch(orderList, () => { orderPage.value = 1; });

        const resetOrderForm = () => { isOrderEditMode.value = false; Object.assign(orderHeader, { orderId: '', placerName: '', orderDate: getToday(), deliveryDate: '' }); orderItems.value = [{ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }]; };
        const calcTotal = (row) => { row.totalLength = (row.metersPerRoll * row.rollCount).toFixed(2); };
        const addOrderItem = () => { orderItems.value.push({ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }); };
        const removeOrderItem = (index) => { if (orderItems.value.length > 1) orderItems.value.splice(index, 1); else ElMessage.warning('至少保留一行！'); };
        const loadOrders = async () => { try { const res = await axios.get('/api/v1/workshops/orders/list'); const map = {}; res.data.forEach(item => { if (!map[item.orderId]) map[item.orderId] = { orderId: item.orderId, orderDate: item.orderDate, deliveryDate: item.deliveryDate, placerName: item.placerName, items: [] }; map[item.orderId].items.push(item); }); orderList.value = Object.values(map); } catch(e) {} };
        const submitOrder = async () => { if (!orderHeader.orderId) { ElMessage.error('请填写订单号！'); return; } loading.value = true; const payload = orderItems.value.map(item => ({ ...orderHeader, ...item })); try { if (isOrderEditMode.value) await axios.put(`/api/v1/workshops/orders/${orderHeader.orderId}`, payload); else await axios.post('/api/v1/workshops/orders/batch', payload); ElMessage.success('操作成功！'); loadOrders(); resetOrderForm(); simDialogVisible.value = false; } catch (error) {} finally { loading.value = false; } };
        const editOrder = (row) => { isOrderEditMode.value = true; Object.assign(orderHeader, { orderId: row.orderId, placerName: row.placerName, orderDate: row.orderDate, deliveryDate: row.deliveryDate }); orderItems.value = JSON.parse(JSON.stringify(row.items)); orderItems.value.forEach(item => calcTotal(item)); window.scrollTo({ top: 0, behavior: 'smooth' }); };
        const deleteOrder = async (orderId) => { try { await ElMessageBox.confirm('数据将永久删除，确认执行？', '警告', { type: 'warning' }); await axios.delete(`/api/v1/workshops/orders/${orderId}`); ElMessage.success('订单已删除'); loadOrders(); if (orderHeader.orderId === orderId) resetOrderForm(); } catch (e) {} };
        const simulateOrder = async () => { if (!orderHeader.orderId) { ElMessage.warning('请先填写完整的订单号！'); return; } loading.value = true; const draftOrdersPayload = orderItems.value.map(item => ({ ...orderHeader, ...item })); try { const res = await axios.post('/api/v1/workshops/estimation/preview', { orderId: orderHeader.orderId, draftOrders: draftOrdersPayload }); simResult.value = res.data; simDialogVisible.value = true; ElMessage.success('🔮 草稿推演完毕！'); } catch (e) {} finally { loading.value = false; } };

        // ==========================================
        // 📊 智能排产大盘 (APS核心引擎)
        // ==========================================
        const estForm = reactive({ orderId: '', itemAdjustments: [] });
        const estResult = ref(null);

        // 应对产能缺失的手工录入弹窗
        const capacityDialogVisible = ref(false);
        const capacityPrompt = reactive({ finishedPartNumber: '', tapePartNumber: '' });
        const manualCap = reactive({ weaving: 0, coex: 0 });

        const fetchInitialDraft = async () => {
            if (!estForm.orderId) return;
            loading.value = true; estResult.value = null;
            try {
                const res = await axios.post('/api/v1/workshops/estimation/preview', estForm);
                estResult.value = res.data;
                ElMessage.info('排产草稿及时间轴已就绪！');
            } catch (error) {
                const msg = error.response?.data || error.response?.data?.message || '';
                if (msg.startsWith("MISSING_PROCESS:")) {
                    const pn = msg.split(":")[1];
                    ElMessageBox.warning(`未找到成品 [${pn}] 的工艺路线定义，请先前往维护绑定关系！`, '防呆拦截');
                    openAddProcess();
                    processForm.finishedPartNumber = pn;
                    activeMenu.value = 'process';
                } else if (msg.startsWith("MISSING_CAPACITY:")) {
                    const parts = msg.split(":");
                    capacityPrompt.finishedPartNumber = parts[1];
                    capacityPrompt.tapePartNumber = parts[2];
                    manualCap.weaving = 0; manualCap.coex = 0;
                    capacityDialogVisible.value = true;
                }
            } finally { loading.value = false; }
        };

        const submitManualCapacity = () => {
            estForm.itemAdjustments = [{
                finishedPartNumber: capacityPrompt.finishedPartNumber,
                manualWeavingCapacity: manualCap.weaving,
                manualCoexCapacity: manualCap.coex
            }];
            capacityDialogVisible.value = false;
            fetchInitialDraft(); // 携带手工干预值重试排产
        };

        const commitFinalScheduleToDb = async () => {
            if (!estResult.value) return;
            try {
                await ElMessageBox.confirm('确认以当前甘特图节点正式下发任务？', '排产复核', { type: 'success' });
                const res = await axios.post('/api/v1/workshops/estimation/commit', estResult.value);
                ElMessage.success(res.data); estResult.value = null; estForm.itemAdjustments = [];
            } catch (error) {}
        };

        // 🌟 生成横排长列时间轴 (甘特图数据模型)
        const ganttRows = computed(() => {
            if (!estResult.value || !estResult.value.details) return [];

            let minTime = new Date('2099-01-01').getTime();
            let maxTime = new Date('2000-01-01').getTime();

            estResult.value.details.forEach(d => {
                if (d.weavingStart) minTime = Math.min(minTime, new Date(d.weavingStart).getTime());
                if (d.weavingEnd) maxTime = Math.max(maxTime, new Date(d.weavingEnd).getTime());
                if (d.coexStart) minTime = Math.min(minTime, new Date(d.coexStart).getTime());
                if (d.coexEnd) maxTime = Math.max(maxTime, new Date(d.coexEnd).getTime());
            });

            // 首尾预留 5% 空白余量
            const span = maxTime - minTime;
            minTime -= span * 0.05; maxTime += span * 0.05;
            const totalSpan = maxTime - minTime;

            const rowsMap = new Map();
            rowsMap.set('W_UNASSIGNED', { id: 'W_UNASSIGNED', label: '🧶 织造 (待指派机台任务池)', tasks: [] });
            machineList.value.forEach(m => rowsMap.set('W_' + m.machineId, { id: 'W_' + m.machineId, label: '机台 ' + m.machineId + '#', tasks: [] }));

            rowsMap.set('C_UNASSIGNED', { id: 'C_UNASSIGNED', label: '🗜️ 共挤 (待指派产线任务池)', tasks: [] });
            lineList.value.forEach(l => rowsMap.set('C_' + l.lineId, { id: 'C_' + l.lineId, label: '产线 ' + l.lineId + '#', tasks: [] }));

            const colors = ['#3b82f6', '#8b5cf6', '#f59e0b', '#10b981', '#ec4899', '#f43f5e'];

            estResult.value.details.forEach((d, idx) => {
                const color = colors[idx % colors.length];

                if (d.weavingStart) {
                    const s = new Date(d.weavingStart).getTime(); const e = new Date(d.weavingEnd).getTime();
                    const task = {
                        ...d, rawStart: d.weavingStart.replace('T',' '), rawEnd: d.weavingEnd.replace('T',' '),
                        left: ((s - minTime) / totalSpan * 100) + '%', width: ((e - s) / totalSpan * 100) + '%',
                        color: color, label: `订单:${d.orderId} | 带坯:${d.tapePartNumber} (${d.tapeMetersNeed.toFixed(0)}m)`,
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
                        color: color, label: `订单:${d.orderId} | 成品:${d.finishedPartNumber} (${d.finishedMeters.toFixed(0)}m)`,
                        typeStr: '共挤排期'
                    };
                    const rId = d.plannedLine ? 'C_' + d.plannedLine : 'C_UNASSIGNED';
                    if(rowsMap.has(rId)) rowsMap.get(rId).tasks.push(task);
                }
            });

            // 过滤掉没有任何任务的轨道，保持图表清爽
            return Array.from(rowsMap.values()).filter(r => r.tasks.length > 0);
        });

        // 厂区暴露状态
        const getStatusClass = (status) => { if (!status) return 'status-other'; if (status.includes('产')) return 'status-producing'; if (status.includes('闲')) return 'status-idle'; if (status.includes('停')) return 'status-stopped'; if (status.includes('修')) return 'status-maintenance'; return 'status-other'; };
        const factoryMachines = computed(() => { return machineList.value.map(m => { const logs = weavingLogList.value.filter(log => String(log.machineId) === String(m.machineId)); logs.sort((a, b) => new Date(b.entryDate) - new Date(a.entryDate)); const latest = logs[0] || {}; return { ...m, currentTape: latest.tapePartNumber || '无任务', currentCapacity: latest.capacityPerDay || 0, operator: latest.operatorName || m.operatorName || '未知', statusClass: getStatusClass(m.machineStatus) }; }); });
        const allWeavingMachines = computed(() => { return factoryMachines.value.sort((a, b) => parseInt(a.machineId) - parseInt(b.machineId)); });
        const factoryLines = computed(() => { return lineList.value.map(l => { const logs = coexLogList.value.filter(log => String(log.lineId) === String(l.lineId)); logs.sort((a, b) => new Date(b.entryDate) - new Date(a.entryDate)); const latest = logs[0] || {}; return { ...l, currentFinished: latest.finishedPartNumber || '无任务', currentSpeed: latest.productionSpeed || 0, currentCapacity: latest.capacityPerDay || 0, statusClass: getStatusClass(l.lineStatus) }; }).sort((a, b) => parseInt(a.lineId) - parseInt(b.lineId)); });

        watch(activeMenu, (newVal) => {
            if (newVal === 'dashboard') { loadMachinesAndLines(); loadWeavingLogs(); loadCoexLogs(); loadInventory(); }
            if (newVal === 'inventory') loadInventory(); if (newVal === 'weaving') loadWeavingLogs();
            if (newVal === 'coex') loadCoexLogs(); if (newVal === 'order') loadOrders(); if (newVal === 'process') loadProcesses();
        });

        return {
            isLoggedIn, currentUser, activeMenu, loading, loginForm, handleLogin, handleLogout, handleMenuSelect, refreshCurrentPage, machineList, lineList,
            weavingForm, weavingLogList, submitWeaving, openEditWeaving, deleteWeaving, resetWeavingForm, weavingFileRef, exportWeavingExcel, handleWeavingImport, weavingSearch, weavingPage, paginatedWeavingLogs, weavingTotal,
            coexForm, coexLogList, submitCoex, openEditCoex, deleteCoex, resetCoexForm, coexFileRef, exportCoexExcel, handleCoexImport, coexSearch, coexPage, paginatedCoexLogs, coexTotal,
            invSearchKeyword, inventoryList, invLoading, invDialogVisible, invSaveLoading, invForm, loadInventory, openAddInv, openEditInv, saveInv, deleteInv, invPage, paginatedInventoryList, invTotal,
            orderHeader, orderItems, isOrderEditMode, orderList, calcTotal, addOrderItem, removeOrderItem, submitOrder, resetOrderForm, editOrder, deleteOrder, simulateOrder, simDialogVisible, simResult, orderPage, paginatedOrderList, orderTotal,
            estForm, estResult, fetchInitialDraft, commitFinalScheduleToDb, ganttRows, capacityDialogVisible, capacityPrompt, manualCap, submitManualCapacity,
            processList, processDialogVisible, processForm, openAddProcess, openEditProcess, saveProcess, deleteProcess, loadProcesses, processFileRef, exportProcessExcel, handleProcessImport, processSearch, processPage, paginatedProcessList, processTotal,
            allWeavingMachines, factoryLines
        };
    }
});
app.use(ElementPlus); app.mount('#app');