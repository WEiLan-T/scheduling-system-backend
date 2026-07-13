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

        axios.interceptors.request.use(config => {
            const token = localStorage.getItem('jwt_token');
            if (token) config.headers['Authorization'] = 'Bearer ' + token;
            return config;
        });

        axios.interceptors.response.use(response => response, error => {
            if (error.response && error.response.status === 403) ElMessage({ message: '⛔ 权限不足！', type: 'error' });
            else if (error.response && error.response.status === 401) { ElMessage.warning('凭证过期，请重新登入。'); handleLogout(); }
            else if (error.response && error.response.data && typeof error.response.data === 'string') ElMessage.error(error.response.data);
            return Promise.reject(error);
        });

        onMounted(() => {
            const token = localStorage.getItem('jwt_token');
            const user = localStorage.getItem('current_user');
            if (token && user) { isLoggedIn.value = true; currentUser.value = user; loadWeavingLogs(); }
        });

        const loginForm = reactive({ username: '', password: '' });
        const handleLogin = async () => {
            if (!loginForm.username || !loginForm.password) return;
            loading.value = true;
            try {
                const res = await axios.post('/api/v1/auth/login', loginForm);
                if (res.data && res.data.includes('eyJ')) {
                    localStorage.setItem('jwt_token', res.data.trim()); localStorage.setItem('current_user', loginForm.username);
                    isLoggedIn.value = true; currentUser.value = loginForm.username; ElMessage.success('核验通过！'); loadWeavingLogs();
                }
            } catch (error) {} finally { loading.value = false; }
        };

        const handleLogout = () => { localStorage.clear(); isLoggedIn.value = false; };
        const handleMenuSelect = (index) => { activeMenu.value = index; estResult.value = null; };

        // ==========================================
        // 🧶 织造车间 MES
        // ==========================================
        const weavingLogList = ref([]);
        const weavingForm = reactive({ id: null, entryDate: getToday(), machineId: 'Z-01', tapePartNumber: '9D2002-TP', workshopId: '织造1车间', warpSpec: '聚酯', weftSpec: 'TPU', bobbinCount: 120, machineStatus: '在产', caliberLimit: '200', adjacentMachine: 'Z-02', operatorName: '张三', capacityPerDay: 260, isDataNormal: true, totalDemand: 1000, remarks: '' });
        const resetWeavingForm = () => Object.assign(weavingForm, { id: null, entryDate: getToday(), machineId: 'Z-01', tapePartNumber: '9D2002-TP', workshopId: '织造1车间', warpSpec: '聚酯', weftSpec: 'TPU', bobbinCount: 120, machineStatus: '在产', caliberLimit: '200', adjacentMachine: 'Z-02', operatorName: '张三', capacityPerDay: 260, isDataNormal: true, totalDemand: 1000, remarks: '' });
        const loadWeavingLogs = async () => { try { const res = await axios.get('/api/v1/workshops/integration/weaving/logs/list'); weavingLogList.value = res.data; } catch (e) {} };
        const submitWeaving = async () => { loading.value = true; try { const res = await axios.post('/api/v1/workshops/integration/weaving/logs', weavingForm); ElMessage.success(res.data); loadWeavingLogs(); resetWeavingForm(); } catch (error) {} finally { loading.value = false; } };
        const openEditWeaving = (row) => { Object.assign(weavingForm, row); window.scrollTo({ top: 0, behavior: 'smooth' }); };
        const deleteWeaving = async (id) => { try { await ElMessageBox.confirm('确认删除并撤回库存增量？', '警告', { type: 'warning' }); const res = await axios.delete(`/api/v1/workshops/integration/weaving/logs/${id}`); ElMessage.success(res.data); loadWeavingLogs(); if (weavingForm.id === id) resetWeavingForm(); } catch (e) {} };

        // ==========================================
        // 🗜️ 共挤车间 MES
        // ==========================================
        const coexLogList = ref([]);
        const coexForm = reactive({ id: null, entryDate: getToday(), lineId: 'G-01', finishedPartNumber: 'U35002', workshopId: '共挤1车间', caliberLimit: '350', lineStatus: '在产', capacityPerDay: 260, isDataNormal: true, tapeDemandQty: 280, tapePartNumber: '9D2002-TP', remarks: '' });
        const resetCoexForm = () => Object.assign(coexForm, { id: null, entryDate: getToday(), lineId: 'G-01', finishedPartNumber: 'U35002', workshopId: '共挤1车间', caliberLimit: '350', lineStatus: '在产', capacityPerDay: 260, isDataNormal: true, tapeDemandQty: 280, tapePartNumber: '9D2002-TP', remarks: '' });
        const loadCoexLogs = async () => { try { const res = await axios.get('/api/v1/workshops/integration/coextrusion/logs/list'); coexLogList.value = res.data; } catch (e) {} };
        const submitCoex = async () => { loading.value = true; try { const res = await axios.post('/api/v1/workshops/integration/coextrusion/logs', coexForm); ElMessage.success(res.data); loadCoexLogs(); resetCoexForm(); } catch (error) {} finally { loading.value = false; } };
        const openEditCoex = (row) => { Object.assign(coexForm, row); window.scrollTo({ top: 0, behavior: 'smooth' }); };
        const deleteCoex = async (id) => { try { await ElMessageBox.confirm('确认删除并退还带坯库存？', '警告', { type: 'warning' }); const res = await axios.delete(`/api/v1/workshops/integration/coextrusion/logs/${id}`); ElMessage.success(res.data); loadCoexLogs(); if (coexForm.id === id) resetCoexForm(); } catch (e) {} };

        // ==========================================
        // 📦 库存调控
        // ==========================================
        const invSearchKeyword = ref(''); const inventoryList = ref([]); const invLoading = ref(false); const invDialogVisible = ref(false); const invSaveLoading = ref(false); const invForm = reactive({ id: null, entryDate: getToday(), tapePartNumber: '', finishedPartNumber: '', currentStockMeters: 0 });
        const loadInventory = async () => { invLoading.value = true; try { const res = await axios.get('/api/v1/workshops/integration/inventory/list', { params: { keyword: invSearchKeyword.value } }); inventoryList.value = res.data; } catch (error) {} finally { invLoading.value = false; } };
        const openAddInv = () => { Object.assign(invForm, { id: null, entryDate: getToday(), tapePartNumber: '', finishedPartNumber: '', currentStockMeters: 0 }); invDialogVisible.value = true; };
        const openEditInv = (row) => { Object.assign(invForm, row); invDialogVisible.value = true; };
        const saveInv = async () => { if (!invForm.tapePartNumber) return; invSaveLoading.value = true; try { await axios.post('/api/v1/workshops/integration/inventory/save', invForm); invDialogVisible.value = false; loadInventory(); ElMessage.success('保存成功'); } catch (error) {} finally { invSaveLoading.value = false; } };
        const deleteInv = async (id) => { try { await axios.delete(`/api/v1/workshops/integration/inventory/${id}`); loadInventory(); ElMessage.success('已删除');} catch (error) {} };

        // ==========================================
        // 🛒 销售订单核心 (CRUD + 模拟)
        // ==========================================
        const isOrderEditMode = ref(false);
        const orderList = ref([]);
        const simDialogVisible = ref(false);
        const simResult = ref(null);

        const orderHeader = reactive({ orderId: '', placerName: '', orderDate: getToday(), deliveryDate: '' });
        const orderItems = ref([{ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }]);

        const resetOrderForm = () => {
            isOrderEditMode.value = false;
            Object.assign(orderHeader, { orderId: '', placerName: '', orderDate: getToday(), deliveryDate: '' });
            orderItems.value = [{ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }];
        };

        const calcTotal = (row) => { row.totalLength = (row.metersPerRoll * row.rollCount).toFixed(2); };
        const addOrderItem = () => { orderItems.value.push({ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }); };
        const removeOrderItem = (index) => { if (orderItems.value.length > 1) orderItems.value.splice(index, 1); else ElMessage.warning('至少保留一行！'); };

        const loadOrders = async () => {
            try {
                const res = await axios.get('/api/v1/workshops/orders/list');
                const map = {};
                res.data.forEach(item => {
                    if (!map[item.orderId]) {
                        map[item.orderId] = { orderId: item.orderId, orderDate: item.orderDate, deliveryDate: item.deliveryDate, placerName: item.placerName, items: [] };
                    }
                    map[item.orderId].items.push(item);
                });
                orderList.value = Object.values(map);
            } catch(e) {}
        };

        const submitOrder = async () => {
            if (!orderHeader.orderId) { ElMessage.error('请填写订单号！'); return; }
            loading.value = true;
            const payload = orderItems.value.map(item => ({ ...orderHeader, ...item }));
            try {
                if (isOrderEditMode.value) await axios.put(`/api/v1/workshops/orders/${orderHeader.orderId}`, payload);
                else await axios.post('/api/v1/workshops/orders/batch', payload);
                ElMessage.success('操作成功！'); loadOrders(); resetOrderForm(); simDialogVisible.value = false;
            } catch (error) {} finally { loading.value = false; }
        };

        const editOrder = (row) => {
            isOrderEditMode.value = true;
            Object.assign(orderHeader, { orderId: row.orderId, placerName: row.placerName, orderDate: row.orderDate, deliveryDate: row.deliveryDate });
            orderItems.value = JSON.parse(JSON.stringify(row.items));
            orderItems.value.forEach(item => calcTotal(item));
            window.scrollTo({ top: 0, behavior: 'smooth' });
        };

        const deleteOrder = async (orderId) => {
            try {
                await ElMessageBox.confirm('数据将永久删除，确认执行？', '警告', { type: 'warning' });
                await axios.delete(`/api/v1/workshops/orders/${orderId}`);
                ElMessage.success('订单已删除'); loadOrders(); if (orderHeader.orderId === orderId) resetOrderForm();
            } catch (e) {}
        };

        // 🌟 核心模拟功能：不写库，将表单拼成 draftOrders 发给推演大脑
        const simulateOrder = async () => {
            if (!orderHeader.orderId) { ElMessage.warning('请先填写完整的订单号和明细！'); return; }
            loading.value = true;
            const draftOrdersPayload = orderItems.value.map(item => ({ ...orderHeader, ...item }));
            try {
                const res = await axios.post('/api/v1/workshops/estimation/preview', {
                    orderId: orderHeader.orderId,
                    draftOrders: draftOrdersPayload
                });
                simResult.value = res.data;
                simDialogVisible.value = true;
                ElMessage.success('🔮 草稿模拟排产推演完毕！');
            } catch (e) {} finally { loading.value = false; }
        };

        // ==========================================
        // 📊 智能排产大盘
        // ==========================================
        const adjForm = reactive({ orderId: '', manualWeavingChangeoverDays: null, manualOperatorRatio: null, manualCoexCapacity: null, manualStartDelayDays: null });
        const estForm = reactive({ orderId: '' });
        const estResult = ref(null);
        const calendarDate = ref(new Date());

        const fetchInitialDraft = async () => {
            if (!estForm.orderId) return;
            loading.value = true; estResult.value = null;
            try {
                const res = await axios.post('/api/v1/workshops/estimation/preview', { orderId: estForm.orderId });
                estResult.value = res.data;
                if (res.data.overallStartDate) { calendarDate.value = new Date(res.data.overallStartDate); }
                ElMessage.info('排产草稿已就绪！');
            } catch (error) {} finally { loading.value = false; }
        };

        const recalculateDraft = async () => {
            if (!estResult.value || !estResult.value.details) return;
            loading.value = true;
            try {
                const payload = {
                    orderId: estResult.value.orderId,
                    itemAdjustments: estResult.value.details.map(row => ({
                        finishedPartNumber: row.finishedPartNumber, manualWeavingChangeoverDays: row.changeoverDays,
                        manualCoexCapacity: row.coexCapacity, manualStartDelayDays: row.startDelay
                    }))
                };
                const res = await axios.post('/api/v1/workshops/estimation/preview', payload);
                estResult.value = res.data; ElMessage.success('已重新排布！');
            } catch(error) {} finally { loading.value = false; }
        };

        const commitFinalScheduleToDb = async () => {
            if (!estResult.value) return;
            try {
                await ElMessageBox.confirm('确认以当前日期落库？', '排产复核', { type: 'success' });
                const res = await axios.post('/api/v1/workshops/estimation/commit', estResult.value);
                ElMessage.success(res.data); estResult.value = null;
            } catch (error) { if (error !== 'cancel') ElMessage.error('落库被拒绝'); }
        };

        const getCalendarTags = (dateStr) => {
            if (!estResult.value || !estResult.value.details) return [];
            const tags = [];
            estResult.value.details.forEach(item => {
                const isWeaving = item.weavingStart && dateStr >= item.weavingStart && dateStr <= item.weavingEnd;
                const isCoex = item.coexStart && dateStr >= item.coexStart && dateStr <= item.coexEnd;
                if (isWeaving && isCoex) { tags.push({ text: '织造+共挤', color: '#8b5cf6' }); }
                else if (isWeaving) { tags.push({ text: '织造期', color: '#3b82f6' }); }
                else if (isCoex) { tags.push({ text: '共挤期', color: '#f97316' }); }
            });
            const uniqueTags = [];
            const seen = new Set();
            for (const tag of tags) { if (!seen.has(tag.text)) { seen.add(tag.text); uniqueTags.push(tag); } }
            return uniqueTags;
        };

        watch(activeMenu, (newVal) => {
            if (newVal === 'inventory') loadInventory();
            if (newVal === 'weaving') loadWeavingLogs();
            if (newVal === 'coex') loadCoexLogs();
            if (newVal === 'order') loadOrders();
        });

        return {
            isLoggedIn, currentUser, activeMenu, loading, loginForm, handleLogin, handleLogout, handleMenuSelect,
            weavingForm, weavingLogList, submitWeaving, openEditWeaving, deleteWeaving, resetWeavingForm,
            coexForm, coexLogList, submitCoex, openEditCoex, deleteCoex, resetCoexForm,
            invSearchKeyword, inventoryList, invLoading, invDialogVisible, invSaveLoading, invForm, loadInventory, openAddInv, openEditInv, saveInv, deleteInv,

            orderHeader, orderItems, isOrderEditMode, orderList, calcTotal, addOrderItem, removeOrderItem, submitOrder, resetOrderForm, editOrder, deleteOrder, simulateOrder, simDialogVisible, simResult,

            estForm, adjForm, estResult, fetchInitialDraft, recalculateDraft, commitFinalScheduleToDb, calendarDate, getCalendarTags
        };
    }
});
app.use(ElementPlus);
app.mount('#app');