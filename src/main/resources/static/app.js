const { createApp, ref, reactive, onMounted, watch } = Vue;
const { ElMessage, ElMessageBox } = ElementPlus;

const app = createApp({
    setup() {
        const getToday = () => {
            const d = new Date();
            return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        };

        const isLoggedIn = ref(false);
        const currentUser = ref('');
        const activeMenu = ref('weaving');
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

        // ================= 基础数据加载 =================
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
            if (token && user) { isLoggedIn.value = true; currentUser.value = user; loadWeavingLogs(); loadMachinesAndLines(); }
        });

        // ================= 认证模块 =================
        const loginForm = reactive({ username: '', password: '' });
        const handleLogin = async () => {
            if (!loginForm.username || !loginForm.password) return;
            loading.value = true;
            try {
                const res = await axios.post('/api/v1/auth/login', loginForm);
                if (res.data && res.data.includes('eyJ')) {
                    localStorage.setItem('jwt_token', res.data.trim()); localStorage.setItem('current_user', loginForm.username);
                    isLoggedIn.value = true; currentUser.value = loginForm.username; ElMessage.success('核验通过！'); loadWeavingLogs(); loadMachinesAndLines();
                }
            } catch (error) { if(error.response && error.response.status === 401) ElMessage.error('账号或密码错误！'); } finally { loading.value = false; }
        };
        const handleLogout = () => { localStorage.clear(); isLoggedIn.value = false; };
        const handleMenuSelect = (index) => { activeMenu.value = index; estResult.value = null; };

        // ================= 页面刷新模块 =================
        const refreshCurrentPage = () => {
            if (activeMenu.value === 'weaving') { loadWeavingLogs(); ElMessage.success('🔄 织造历史台账同步刷新完成'); }
            else if (activeMenu.value === 'process') { loadProcesses(); ElMessage.success('🔄 工艺路线参数配置库已同步刷新'); }
            else if (activeMenu.value === 'coex') { loadCoexLogs(); ElMessage.success('🔄 共挤历史台账同步刷新完成'); }
            else if (activeMenu.value === 'inventory') { loadInventory(); ElMessage.success('🔄 虚拟分批库存大盘已刷新'); }
            else if (activeMenu.value === 'order') { loadOrders(); ElMessage.success('🔄 销售合同档案订单库已刷新'); }
            else if (activeMenu.value === 'estimation') {
                if (estForm.orderId) { fetchInitialDraft(); } else { ElMessage.success('🔄 排产控制中心已就绪'); }
            }
        };

        // ==========================================
        // 🧶 织造车间 MES (修复去重版)
        // ==========================================
        const weavingLogList = ref([]);
        const weavingFileRef = ref(null);

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

        const exportWeavingExcel = async () => {
            try { const res = await axios.get('/api/v1/workshops/integration/weaving/export', { responseType: 'blob' }); const blob = new Blob([res.data]); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '织造车间产能明细汇总.xlsx'; link.click(); ElMessage.success('📥 织造 17 列明细大盘导出成功！'); } catch(e) { ElMessage.error('导出失败'); }
        };
        const handleWeavingImport = async (e) => {
            const file = e.target.files[0]; if (!file) return; const fd = new FormData(); fd.append('file', file); loading.value = true;
            try { const res = await axios.post('/api/v1/workshops/integration/weaving/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } }); ElMessage.success(res.data); loadWeavingLogs(); } catch(err) {} finally { loading.value = false; e.target.value = ''; }
        };

        // ==========================================
        // 🗜️ 共挤车间 MES
        // ==========================================
        const coexLogList = ref([]);
        const coexFileRef = ref(null);

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

        const exportCoexExcel = async () => {
            try { const res = await axios.get('/api/v1/workshops/integration/coextrusion/export', { responseType: 'blob' }); const blob = new Blob([res.data]); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '共挤车间历史台账汇总.xlsx'; link.click(); ElMessage.success('📥 共挤历史台账导出成功！'); } catch(e) { ElMessage.error('导出失败'); }
        };
        const handleCoexImport = async (e) => {
            const file = e.target.files[0]; if (!file) return; const fd = new FormData(); fd.append('file', file); loading.value = true;
            try { const res = await axios.post('/api/v1/workshops/integration/coextrusion/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } }); ElMessage.success(res.data); loadCoexLogs(); } catch(err) {} finally { loading.value = false; e.target.value = ''; }
        };

        // ==========================================
        // 📦 虚拟库存总览与控制台
        // ==========================================
        const invSearchKeyword = ref(''); const inventoryList = ref([]); const invLoading = ref(false); const invDialogVisible = ref(false); const invSaveLoading = ref(false); const invForm = reactive({ id: null, entryDate: getToday(), tapePartNumber: '', tapeNumber: '', finishedPartNumber: '', currentStockMeters: 0 });
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
        // ⚙️ 工艺路线参数配置库
        // ==========================================
        const processList = ref([]); const processFileRef = ref(null); const processDialogVisible = ref(false);
        const processForm = reactive({ id: null, finishedPartNumber: '', tapePartNumber: '', warpSpec: '', weftSpec: '' });

        const loadProcesses = async () => { try { const res = await axios.get('/api/v1/workshops/integration/process/list'); processList.value = res.data; } catch (e) {} };
        const openAddProcess = () => { Object.assign(processForm, { id: null, finishedPartNumber: '', finishedModelSpec: '', tapePartNumber: '', tapeModelSpec: '', warpSpec: '', weftSpec: '' }); processDialogVisible.value = true; };
        const openEditProcess = (row) => { Object.assign(processForm, row); processDialogVisible.value = true; };
        const saveProcess = async () => { if (!processForm.finishedPartNumber || !processForm.tapePartNumber) { ElMessage.error('成品零件号与带坯零件号均不能为空！'); return; } try { const res = await axios.post('/api/v1/workshops/integration/process/save', processForm); ElMessage.success(res.data); processDialogVisible.value = false; loadProcesses(); } catch (e) {} };
        const deleteProcess = async (id) => { try { await ElMessageBox.confirm('解除工艺绑定后将影响排产，确认？', '警告', { type: 'warning' }); const res = await axios.delete(`/api/v1/workshops/integration/process/${id}`); ElMessage.success(res.data); loadProcesses(); } catch (e) {} };

        const exportProcessExcel = async () => { try { const response = await axios.get('/api/v1/workshops/integration/process/export', { responseType: 'blob' }); const blob = new Blob([response.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '工艺路线数据大盘.xlsx'; link.click(); ElMessage.success('📥 工艺大盘导出成功！'); } catch (error) { ElMessage.error('导出失败，请检查服务器！'); } };
        const handleProcessImport = async (event) => { const file = event.target.files[0]; if (!file) return; const formData = new FormData(); formData.append('file', file); loading.value = true; try { const response = await axios.post('/api/v1/workshops/integration/process/import', formData, { headers: { 'Content-Type': 'multipart/form-data' } }); ElMessage.success(response.data); loadProcesses(); } catch (error) {} finally { loading.value = false; event.target.value = ''; } };

        // ==========================================
        // 🛒 销售订单核心
        // ==========================================
        const isOrderEditMode = ref(false); const orderList = ref([]); const simDialogVisible = ref(false); const simResult = ref(null);
        const orderHeader = reactive({ orderId: '', placerName: '', orderDate: getToday(), deliveryDate: '' });
        const orderItems = ref([{ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }]);

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
        // 📊 智能排产大盘
        // ==========================================
        const adjForm = reactive({ orderId: '', manualWeavingChangeoverDays: null, manualOperatorRatio: null, manualCoexCapacity: null, manualStartDelayDays: null });
        const estForm = reactive({ orderId: '' }); const estResult = ref(null); const calendarDate = ref(new Date());

        const fetchInitialDraft = async () => { if (!estForm.orderId) return; loading.value = true; estResult.value = null; try { const res = await axios.post('/api/v1/workshops/estimation/preview', { orderId: estForm.orderId }); estResult.value = res.data; if (res.data.overallStartDate) { calendarDate.value = new Date(res.data.overallStartDate); } ElMessage.info('排产草稿已就绪！'); } catch (error) {} finally { loading.value = false; } };
        const recalculateDraft = async () => { if (!estResult.value || !estResult.value.details) return; loading.value = true; try { const payload = { orderId: estResult.value.orderId, itemAdjustments: estResult.value.details.map(row => ({ finishedPartNumber: row.finishedPartNumber, manualWeavingChangeoverDays: row.changeoverDays, manualCoexCapacity: row.coexCapacity, manualStartDelayDays: row.startDelay })) }; const res = await axios.post('/api/v1/workshops/estimation/preview', payload); estResult.value = res.data; ElMessage.success('已重新排布！'); } catch(error) {} finally { loading.value = false; } };
        const commitFinalScheduleToDb = async () => { if (!estResult.value) return; try { await ElMessageBox.confirm('确认以当前日期落库？', '排产复核', { type: 'success' }); const res = await axios.post('/api/v1/workshops/estimation/commit', estResult.value); ElMessage.success(res.data); estResult.value = null; } catch (error) { if (error !== 'cancel') ElMessage.error('落库被拒绝'); } };

        const getCalendarTags = (dateStr) => {
            if (!estResult.value || !estResult.value.details) return [];
            const tags = [];
            estResult.value.details.forEach(item => {
                const isWeaving = item.weavingStart && dateStr >= item.weavingStart && dateStr <= item.weavingEnd;
                const isCoex = dateStr >= item.coexStart && dateStr <= item.coexEnd;
                if (isWeaving && isCoex) { tags.push({ text: '织造+共挤', color: '#8b5cf6' }); }
                else if (isWeaving) { tags.push({ text: '织造期', color: '#3b82f6' }); }
                else if (isCoex) { tags.push({ text: '共挤期', color: '#f97316' }); }
            });
            const uniqueTags = []; const seen = new Set();
            for (const tag of tags) { if (!seen.has(tag.text)) { seen.add(tag.text); uniqueTags.push(tag); } }
            return uniqueTags;
        };
        const getMachineDetails = (machineId) => { const m = machineList.value.find(x => x.machineId === machineId); if (!m) return ''; return `所属: ${m.workshopId} | 状态: ${m.machineStatus} | 口径限制: ${m.caliberLimit || '无'} \n 当前经纬: ${m.warpSpec || '-'}/${m.weftSpec || '-'}`; };
        const getLineDetails = (lineId) => { const l = lineList.value.find(x => x.lineId === lineId); if (!l) return ''; return `所属: ${l.workshopId} | 状态: ${l.lineStatus} | 口径限制: ${l.caliberLimit || '无'}`; };

        // ================= 视图监听器 =================
        watch(activeMenu, (newVal) => {
            if (newVal === 'inventory') loadInventory();
            if (newVal === 'weaving') loadWeavingLogs();
            if (newVal === 'coex') loadCoexLogs();
            if (newVal === 'order') loadOrders();
            if (newVal === 'process') loadProcesses();
        });

        // 🌟 暴漏给模板的所有变量与方法 (已严谨去重核对)
        return {
            isLoggedIn, currentUser, activeMenu, loading, loginForm, handleLogin, handleLogout, handleMenuSelect,
            refreshCurrentPage, machineList, lineList,
            weavingForm, weavingLogList, submitWeaving, openEditWeaving, deleteWeaving, resetWeavingForm, weavingFileRef, exportWeavingExcel, handleWeavingImport,
            coexForm, coexLogList, submitCoex, openEditCoex, deleteCoex, resetCoexForm, coexFileRef, exportCoexExcel, handleCoexImport,
            invSearchKeyword, inventoryList, invLoading, invDialogVisible, invSaveLoading, invForm, loadInventory, openAddInv, openEditInv, saveInv, deleteInv,
            orderHeader, orderItems, isOrderEditMode, orderList, calcTotal, addOrderItem, removeOrderItem, submitOrder, resetOrderForm, editOrder, deleteOrder, simulateOrder, simDialogVisible, simResult,
            estForm, adjForm, estResult, fetchInitialDraft, recalculateDraft, commitFinalScheduleToDb, calendarDate, getCalendarTags, getMachineDetails, getLineDetails,
            processList, processDialogVisible, processForm, openAddProcess, openEditProcess, saveProcess, deleteProcess, loadProcesses, processFileRef, exportProcessExcel, handleProcessImport
        };
    }
});
app.use(ElementPlus); app.mount('#app');