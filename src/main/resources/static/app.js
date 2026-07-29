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
                const t = parseDateEnd(log.entryDate);
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

        const isLoggedIn = ref(false);
        const currentUser = ref('');
        const activeMenu = ref('order-dashboard');
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
            else if (activeMenu.value === 'order-dashboard') { loadOrders(); ElMessage.success('🔄 订单交期全景大盘已更新'); }
            else if (activeMenu.value === 'execution') {
                loadWeavingLogs(); loadCoexLogs(); loadOrders(); loadAllSchedulePlans();
                ElMessage.success('🔄 全厂订单执行仪表板实时数据获取成功！');
            }
            else if (activeMenu.value === 'estimation') {
                if (estForm.orderId) { fetchInitialDraft(); } else { ElMessage.success('🔄 排产控制中心已就绪'); }
            }
        };

        // ==========================================
        // 🧶 织造车间 MES
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

        watch(() => weavingForm.tapePartNumber, (newVal) => {
            if (newVal && processList.value.length > 0) {
                const proc = processList.value.find(p => p.tapePartNumber === newVal);
                if (proc) {
                    if (!weavingForm.modelSpec && proc.tapeModelSpec) weavingForm.modelSpec = proc.tapeModelSpec;
                    if (!weavingForm.warpSpec && proc.warpSpec) weavingForm.warpSpec = proc.warpSpec;
                    if (!weavingForm.weftSpec && proc.weftSpec) weavingForm.weftSpec = proc.weftSpec;
                }
            }
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
        // 🗜️ 共挤车间 MES
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

        //==========================================
        // 🛒 销售订单核心
        // ==========================================
        const isOrderEditMode = ref(false); const orderList = ref([]); const simDialogVisible = ref(false); const simResult = ref(null);
        const orderHeader = reactive({ orderId: '', customerName: '', salesperson: '', orderDate: getToday(), deliveryDate: '' });
        const orderItems = ref([{ finishedPartNumber: '', productName: '', modelSpec: '', color: '', unfinishedMeters: 0, metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }]);
        const orderPage = ref(1); const orderFileRef = ref(null);

        const paginatedOrderList = computed(() => {
            const start = (orderPage.value - 1) * 10;
            return orderList.value.slice(start, start + 10);
        });
        const orderTotal = computed(() => orderList.value.length);
        watch(orderList, () => { orderPage.value = 1; });

        const resetOrderForm = () => { isOrderEditMode.value = false; Object.assign(orderHeader, { orderId: '', customerName: '', salesperson: '', orderDate: getToday(), deliveryDate: '' }); orderItems.value = [{ finishedPartNumber: '', productName: '', modelSpec: '', color: '', unfinishedMeters: 0, metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }]; };
        const calcTotal = (row) => { row.totalLength = (row.metersPerRoll * row.rollCount).toFixed(2); };
        const addOrderItem = () => { orderItems.value.push({ finishedPartNumber: '', productName: '', modelSpec: '', color: '', unfinishedMeters: 0, metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }); };
        const removeOrderItem = (index) => { if (orderItems.value.length > 1) orderItems.value.splice(index, 1); else ElMessage.warning('至少保留一行！'); };

        const loadOrders = async () => {
            try {
                const res = await axios.get('/api/v1/workshops/orders/list');
                const map = {};
                res.data.forEach(item => {
                    if (!map[item.orderId]) map[item.orderId] = { orderId: item.orderId, orderDate: item.orderDate, deliveryDate: item.deliveryDate, customerName: item.customerName, salesperson: item.salesperson, items: [] };
                    map[item.orderId].items.push(item);
                });
                orderList.value = Object.values(map);
            } catch(e) {}
            // 附加排产状态
            try {
                const schedRes = await axios.get('/api/v1/workshops/estimation/schedule-summary');
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
            }
        };

        const submitOrder = async () => {
            if (!orderHeader.orderId) { ElMessage.error('请填写订单号！'); return; }
            loading.value = true; const payload = orderItems.value.map(item => ({ ...orderHeader, ...item }));
            try {
                if (isOrderEditMode.value) await axios.put(`/api/v1/workshops/orders/${orderHeader.orderId}`, payload);
                else await axios.post('/api/v1/workshops/orders/batch', payload);
                ElMessage.success('操作成功！'); loadOrders(); resetOrderForm(); simDialogVisible.value = false;
            } catch (error) {} finally { loading.value = false; }
        };

        const editOrder = (row) => {
            isOrderEditMode.value = true;
            Object.assign(orderHeader, { orderId: row.orderId, customerName: row.customerName, salesperson: row.salesperson, orderDate: row.orderDate, deliveryDate: row.deliveryDate });
            orderItems.value = JSON.parse(JSON.stringify(row.items));
            orderItems.value.forEach(item => calcTotal(item)); window.scrollTo({ top: 0, behavior: 'smooth' });
        };
        const deleteOrder = async (orderId) => { try { await ElMessageBox.confirm('数据将永久删除，确认执行？', '警告', { type: 'warning' }); await axios.delete(`/api/v1/workshops/orders/${orderId}`); ElMessage.success('订单已删除'); loadOrders(); if (orderHeader.orderId === orderId) resetOrderForm(); } catch (e) {} };
        const simulateOrder = async () => { if (!orderHeader.orderId) { ElMessage.warning('请先填写完整的订单号！'); return; } loading.value = true; const draftOrdersPayload = orderItems.value.map(item => ({ ...orderHeader, ...item })); try { const res = await axios.post('/api/v1/workshops/estimation/preview', { orderId: orderHeader.orderId, draftOrders: draftOrdersPayload }); simResult.value = res.data; simDialogVisible.value = true; ElMessage.success('🔮 草稿推演完毕！'); } catch (e) {} finally { loading.value = false; } };

        const exportOrderExcel = async () => { try { const res = await axios.get('/api/v1/workshops/orders/export', { responseType: 'blob' }); const blob = new Blob([res.data]); const link = document.createElement('a'); link.href = window.URL.createObjectURL(blob); link.download = '生产订单排产明细.xlsx'; link.click(); ElMessage.success('📥 导出成功！'); } catch(e) { ElMessage.error('导出失败'); } };
        const handleOrderImport = async (e) => { const file = e.target.files[0]; if (!file) return; const fd = new FormData(); fd.append('file', file); loading.value = true; try { const res = await axios.post('/api/v1/workshops/orders/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } }); ElMessage.success(res.data); loadOrders(); } catch(err) {} finally { loading.value = false; e.target.value = ''; } };

        // ==========================================
        // 📊 智能排产大盘 (APS核心引擎)
        // ==========================================
        const estForm = reactive({
            orderId: '',
            itemAdjustments: [],
            globalBufferDays: 3,
            weavingAdvanceDays: 2
        });
        const estResult = ref(null);
        const capacityDialogVisible = ref(false);
        const capacityPrompt = reactive({ finishedPartNumber: '', tapePartNumber: '' });
        const manualCap = reactive({ weaving: 0, coex: 0 });

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
                    weavingAdvanceDays: estForm.weavingAdvanceDays
                });
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
                const msg = error.response?.data || '';
                ElMessage.error(typeof msg === 'string' ? msg : '多订单排产失败');
            } finally {
                loading.value = false;
            }
        };

        const loadScheduleSummary = async () => {
            try {
                const res = await axios.get('/api/v1/workshops/estimation/schedule-summary');
                scheduleSummaryMap.value = {};
                if (Array.isArray(res.data)) {
                    res.data.forEach(item => {
                        scheduleSummaryMap.value[item.orderId] = item;
                    });
                }
            } catch (e) {}
        };

        const scheduleSummaryMap = ref({});

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
            fetchInitialDraft();
        };

        const commitFinalScheduleToDb = async () => {
            if (!estResult.value) return;
            try {
                await ElMessageBox.confirm('确认以当前甘特图节点正式下发任务？', '排产复核', { type: 'success' });
                const res = await axios.post('/api/v1/workshops/estimation/commit', estResult.value);
                ElMessage.success(res.data); estResult.value = null; estForm.itemAdjustments = [];
            } catch (error) {}
        };

        const ganttTimeline = computed(() => {
            if (!estResult.value || !estResult.value.details) return [];
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

            const markers = [];
            const days = span / (1000 * 60 * 60 * 24);
            let step = 1;
            if (days > 15) step = Math.ceil(days / 10);

            for (let t = minTime; t <= maxTime; t += step * 24 * 3600 * 1000) {
                const dateObj = new Date(t);
                markers.push({
                    left: ((t - minTime) / (maxTime - minTime) * 100) + '%',
                    label: `${dateObj.getMonth() + 1}月${dateObj.getDate()}日`
                });
            }
            return markers;
        });

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
            const span = maxTime - minTime;
            minTime -= span * 0.05; maxTime += span * 0.05;
            const totalSpan = maxTime - minTime;
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
            return Array.from(rowsMap.values()).filter(r => r.tasks.length > 0);
        });

        // ==========================================
        // 📊 核心数据抓取：关联订单与工艺推演
        // ==========================================
        const cleanId = (id) => String(id).replace(/[#线]/g, '').trim();
        const getStatusClass = (status) => { if (!status) return 'status-other'; if (status.includes('产')) return 'status-producing'; if (status.includes('闲')) return 'status-idle'; if (status.includes('停')) return 'status-stopped'; if (status.includes('修')) return 'status-maintenance'; return 'status-other'; };

        const computedMachineStatus = (machineId, logList, idKey) => {
            const logs = logList.filter(log => cleanId(log[idKey]) === cleanId(machineId));
            if (logs.length === 0) return '空闲';

            logs.sort((a, b) => new Date(b.entryDate).getTime() - new Date(a.entryDate).getTime());

            let globalMaxTime = 0;
            logList.forEach(l => {
                const t = new Date(l.entryDate + 'T00:00:00').getTime();
                if (t > globalMaxTime) globalMaxTime = t;
            });

            const latestLog = logs[0];
            const latestLogTime = new Date(latestLog.entryDate + 'T00:00:00').getTime();
            const ONE_DAY = 24 * 3600 * 1000;

            const hasRecentData = (globalMaxTime - latestLogTime) <= ONE_DAY;

            const r = latestLog.remarks || '';
            if (r.includes('了机')) return '空闲';
            if (r.includes('开头机')) return '在产';

            return hasRecentData ? '在产' : '空闲';
        };

        // 🌟 性能优化：缓存所有机台状态，避免 O(N^2) 重复计算
        const machineStatusMap = computed(() => {
            const map = {};
            machineList.value.forEach(m => { map[cleanId(m.machineId)] = computedMachineStatus(m.machineId, weavingLogList.value, 'machineId'); });
            return map;
        });
        const lineStatusMap = computed(() => {
            const map = {};
            lineList.value.forEach(l => { map[cleanId(l.lineId)] = computedMachineStatus(l.lineId, coexLogList.value, 'lineId'); });
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
                    const logs = weavingLogList.value.filter(log => cleanId(log.machineId) === cleanId(m.machineId));
                    logs.sort((a, b) => new Date(b.entryDate).getTime() - new Date(a.entryDate).getTime());
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
                accum = weavingLogList.value
                    .filter(l => cleanId(l.machineId) === cleanId(machineId) && l.tapePartNumber === tapePn && new Date(l.entryDate + 'T00:00:00').getTime() >= orderTime)
                    .reduce((sum, l) => sum + (l.capacityPerDay || 0), 0);
            } else {
                const taskLogs = weavingLogList.value.filter(l => cleanId(l.machineId) === cleanId(machineId) && l.tapePartNumber === tapePn);
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
                    const logs = coexLogList.value.filter(log => cleanId(log.lineId) === cleanId(l.lineId));
                    logs.sort((a, b) => new Date(b.entryDate).getTime() - new Date(a.entryDate).getTime());
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
            const taskLogs = coexLogList.value.filter(log => cleanId(log.lineId) === cleanId(lineId) && log.orderNumber === orderId && log.finishedPartNumber === finishedPn);
            accum = taskLogs.reduce((sum, log) => sum + (log.capacityPerDay || 0), 0);

            if (!total) total = accum > 0 ? accum * 1.5 : 1000;
            return { total, accum };
        };

        // ==========================================
        // 📊 核心仪表盘：织造 & 共挤产线卡片
        // ==========================================
        const factoryMachines = computed(() => {
            return machineList.value.map(m => {
                const logs = weavingLogList.value.filter(log => cleanId(log.machineId) === cleanId(m.machineId));
                logs.sort((a, b) => new Date(b.entryDate) - new Date(a.entryDate));
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
                    statusClass: getStatusClass(realStatus)
                };
            });
        });

        const allWeavingMachines = computed(() => {
            return factoryMachines.value.sort((a, b) => parseInt(cleanId(a.machineId)) - parseInt(cleanId(b.machineId)));
        });

        const factoryLines = computed(() => {
            return lineList.value.map(l => {
                const logs = coexLogList.value.filter(log => cleanId(log.lineId) === cleanId(l.lineId));
                logs.sort((a, b) => new Date(b.entryDate) - new Date(a.entryDate));
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
                    statusClass: getStatusClass(realStatus)
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
                const logs = weavingLogList.value.filter(log => cleanId(log.machineId) === cleanId(m.machineId));
                logs.sort((a, b) => new Date(b.entryDate) - new Date(a.entryDate));
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
                    rows.push({ id: 'W_' + m.machineId, label: `织造 ${m.machineId}#`, type: 'weaving', task: null });
                }
            });

            lineList.value.forEach(l => {
                const logs = coexLogList.value.filter(log => cleanId(log.lineId) === cleanId(l.lineId));
                logs.sort((a, b) => new Date(b.entryDate) - new Date(a.entryDate));
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
                        task: { partNumber: finishedPn, orderId, accum: progress.accum, total: progress.total, start: now - pastMs, end: now + futureMs, capPerDay }
                    });
                } else {
                    rows.push({ id: 'C_' + l.lineId, label: `共挤 ${l.lineId}#`, type: 'coex', task: null });
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
                const res = await axios.get('/api/v1/workshops/estimation/schedule-summary');
                if (Array.isArray(res.data)) {
                    allSchedulePlans.value = res.data;
                }
            } catch (e) {}
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
                const adjustments = estResult.value.details
                    .filter(d => d.manualWeavingCap || d.manualCoexCap)
                    .map(d => ({
                        finishedPartNumber: d.finishedPartNumber,
                        manualWeavingCapacity: d.manualWeavingCap || undefined,
                        manualCoexCapacity: d.manualCoexCap || undefined
                    }));
                
                const reqBody = {
                    orderId: estResult.value.orderId,
                    globalBufferDays: estForm.globalBufferDays,
                    weavingAdvanceDays: estForm.weavingAdvanceDays,
                    itemAdjustments: adjustments.length > 0 ? adjustments : undefined
                };
                
                const res = await axios.post('/api/v1/workshops/estimation/preview', reqBody);
                estResult.value = res.data;
                ElMessage.success('已根据人工调整参数重新推演！');
            } catch (error) {
                ElMessage.error('重新推演失败，请检查参数');
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

        const drillDownGanttTimeline = computed(() => {
            if (!drillDownDetail.value || !drillDownDetail.value.details) return [];
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
            const markers = [];
            const days = span / (1000 * 60 * 60 * 24);
            let step = 1;
            if (days > 15) step = Math.ceil(days / 10);
            for (let t = minTime; t <= maxTime; t += step * 24 * 3600 * 1000) {
                const dateObj = new Date(t);
                markers.push({ left: ((t - minTime) / (maxTime - minTime) * 100) + '%', label: `${dateObj.getMonth() + 1}月${dateObj.getDate()}日` });
            }
            return markers;
        });

        const drillDownGanttRows = computed(() => {
            if (!drillDownDetail.value || !drillDownDetail.value.details) return [];
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
            const totalSpan = maxTime - minTime;
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
                        color: color, label: `带坯:${d.tapePartNumber} (${d.tapeMetersNeed.toFixed(0)}m)`,
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
                        color: color, label: `成品:${d.finishedPartNumber} (${d.finishedMeters.toFixed(0)}m)`,
                        typeStr: '共挤排期'
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
                const res = await axios.post('/api/v1/workshops/estimation/preview', { orderId: orderId });
                drillDownDetail.value = res.data;
            } catch (error) {
                const msg = error.response?.data || '';
                if (typeof msg === 'string' && msg.startsWith('MISSING_PROCESS:')) {
                    ElMessage.warning('该订单缺少工艺路线定义，请先配置工艺参数');
                } else if (typeof msg === 'string' && msg.startsWith('MISSING_CAPACITY:')) {
                    ElMessage.warning('该订单缺少产能数据，请先录入历史产能');
                } else {
                    ElMessage.error('获取排产详情失败');
                }
            } finally {
                drillDownLoading.value = false;
            }
        };

        // 🌟 将新页面加入到路由刷新监听器中
        const loadedPages = ref(new Set());
        watch(activeMenu, (newVal) => {
            if (loadedPages.value.has(newVal)) return;
            loadedPages.value.add(newVal);
            if (newVal === 'dashboard') { loadMachinesAndLines(); loadWeavingLogs(); loadCoexLogs(); loadInventory(); }
            if (newVal === 'inventory') loadInventory(); 
            if (newVal === 'weaving') loadWeavingLogs();
            if (newVal === 'coex') loadCoexLogs(); 
            if (newVal === 'order') loadOrders(); 
            if (newVal === 'process') loadProcesses();
            if (newVal === 'execution') { loadWeavingLogs(); loadCoexLogs(); loadOrders(); loadAllSchedulePlans(); }
            if (newVal === 'order-dashboard') { loadOrders(); loadScheduleSummary(); }
        });



        return {
            isLoggedIn, currentUser, activeMenu, loading, loginForm, handleLogin, handleLogout, handleMenuSelect, refreshCurrentPage, machineList, lineList,
            weavingForm, weavingLogList, submitWeaving, openEditWeaving, deleteWeaving, resetWeavingForm, weavingFileRef, exportWeavingExcel, handleWeavingImport, weavingSearch, weavingPage, paginatedWeavingLogs, weavingTotal,
            coexForm, coexLogList, submitCoex, openEditCoex, deleteCoex, resetCoexForm, coexFileRef, exportCoexExcel, handleCoexImport, coexSearch, coexPage, paginatedCoexLogs, coexTotal,
            invSearchKeyword, inventoryList, invLoading, invDialogVisible, invSaveLoading, invForm, loadInventory, openAddInv, openEditInv, saveInv, deleteInv, invPage, paginatedInventoryList, invTotal,
            orderHeader, orderItems, isOrderEditMode, orderList, calcTotal, addOrderItem, removeOrderItem, submitOrder, resetOrderForm, editOrder, deleteOrder, simulateOrder, simDialogVisible, simResult, orderPage, paginatedOrderList, orderTotal,
            estForm, estResult, fetchInitialDraft, commitFinalScheduleToDb, ganttRows, ganttTimeline, capacityDialogVisible, capacityPrompt, manualCap, submitManualCapacity,
            processList, processDialogVisible, processForm, openAddProcess, openEditProcess, saveProcess, deleteProcess, loadProcesses, processFileRef, exportProcessExcel, handleProcessImport, processSearch, processPage, paginatedProcessList, processTotal,
            allWeavingMachines, factoryLines,
            executionCurrentTime, dashboardTimeline, currentLineX, dashboardRowsWithPos, dashboardViewDays,
            orderFileRef, exportOrderExcel, handleOrderImport, orderViewDays, orderGanttRowsWithPos, orderGanttTimeline, orderGanttCurrentLineX,
            multiOrderMode, selectedOrderIds, fetchMultiOrderSchedule, multiOrderResult,
            scheduleSummaryMap, loadScheduleSummary, orderSuggestions,
            drillDownDialogVisible, drillDownOrderId, drillDownDetail, drillDownLoading,
            drillDownGanttTimeline, drillDownGanttRows, openOrderDetail,
            allSchedulePlans, loadAllSchedulePlans, dashboardRowsWithPlanOverlay,
            applyManualAdjustments
        };
    }
});
app.use(ElementPlus); app.mount('#app');