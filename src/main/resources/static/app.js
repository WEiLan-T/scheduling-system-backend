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

        // 🛡️ Axios 拦截器
        axios.interceptors.request.use(config => {
            const token = localStorage.getItem('jwt_token');
            if (token) config.headers['Authorization'] = 'Bearer ' + token;
            return config;
        });

        axios.interceptors.response.use(response => response, error => {
            if (error.response && error.response.status === 403) {
                ElMessage({ message: '⛔ 权限不足：您无法执行当前车间或层级的操作！', type: 'error', duration: 4000 });
            } else if (error.response && error.response.status === 401) {
                ElMessage.warning('安保凭证已过期，请重新登入。');
                handleLogout();
            } else if (error.response && error.response.data && typeof error.response.data === 'string') {
                ElMessage.error(error.response.data);
            } else {
                ElMessage.error('系统通信异常，请检查后端服务是否启动。');
            }
            return Promise.reject(error);
        });

        onMounted(() => {
            const token = localStorage.getItem('jwt_token');
            const user = localStorage.getItem('current_user');
            if (token && user) { isLoggedIn.value = true; currentUser.value = user; }
        });

        // 🔒 登录认证
        const loginForm = reactive({ username: '', password: '' });
        const handleLogin = async () => {
            if (!loginForm.username || !loginForm.password) { ElMessage.warning("请填写账号密码"); return; }
            loading.value = true;
            try {
                const res = await axios.post('/api/v1/auth/login', loginForm);
                if (res.data && res.data.includes('eyJ')) {
                    const token = res.data.substring(res.data.indexOf('eyJ')).trim();
                    localStorage.setItem('jwt_token', token);
                    localStorage.setItem('current_user', loginForm.username);
                    isLoggedIn.value = true; currentUser.value = loginForm.username;
                    ElMessage.success('核验通过，欢迎进入 MES & APS 系统！');
                }
            } catch (error) {
                if(error.response && error.response.status === 401) ElMessage.error('账号或密码错误！');
            } finally { loading.value = false; }
        };

        const handleLogout = () => {
            localStorage.clear(); isLoggedIn.value = false; currentUser.value = '';
            loginForm.username = ''; loginForm.password = '';
        };

        const handleMenuSelect = (index) => {
            activeMenu.value = index;
            entryResult.value = ''; estResult.value = null; orderResult.value = ''; invResult.value = '';
        };

        // ==========================================
        // 🧶 织造车间 MES 聚合录入
        // ==========================================
        const weavingForm = reactive({
            entryDate: getToday(), machineId: 'z1', tapePartNumber: '9D2002', workshopId: '织造1车间',
            warpSpec: '聚酯', weftSpec: 'TPU', bobbinCount: 120, machineStatus: '在产', caliberLimit: '200',
            adjacentMachine: 'z2', operatorName: '李四', capacityPerDay: 260, isDataNormal: true, totalDemand: 1000, remarks: ''
        });
        const entryResult = ref('');
        const submitWeaving = async () => {
            loading.value = true; entryResult.value = '';
            try {
                const res = await axios.post('/api/v1/workshops/integration/weaving/logs', weavingForm);
                entryResult.value = res.data; ElMessage.success('织造台账归档成功，库存已同步增量！');
            } catch (error) {} finally { loading.value = false; }
        };

        // ==========================================
        // 🗜️ 共挤车间 MES 聚合录入
        // ==========================================
        const coexForm = reactive({
            entryDate: getToday(), lineId: 'g1', finishedPartNumber: 'U35002', workshopId: '共挤1车间',
            caliberLimit: '350', lineStatus: '在产', capacityPerDay: 260, isDataNormal: true,
            tapeDemandQty: 260, tapePartNumber: '9D2002', remarks: ''
        });
        const submitCoex = async () => {
            loading.value = true; entryResult.value = '';
            try {
                const res = await axios.post('/api/v1/workshops/integration/coextrusion/logs', coexForm);
                entryResult.value = res.data; ElMessage.success('共挤台账归档成功，已自动扣减带坯！');
            } catch (error) {} finally { loading.value = false; }
        };

        // ==========================================
        // 📦 虚拟库存调控中心 (CRUD)
        // ==========================================
        const invSearchKeyword = ref('');
        const inventoryList = ref([]);
        const invLoading = ref(false);
        const invDialogVisible = ref(false);
        const invSaveLoading = ref(false);
        const invForm = reactive({ id: null, entryDate: getToday(), tapePartNumber: '', finishedPartNumber: '', currentStockMeters: 0 });

        const loadInventory = async () => {
            invLoading.value = true;
            try {
                const res = await axios.get('/api/v1/workshops/integration/inventory/list', { params: { keyword: invSearchKeyword.value } });
                inventoryList.value = res.data;
            } catch (error) {} finally { invLoading.value = false; }
        };

        const openAddInv = () => {
            Object.assign(invForm, { id: null, entryDate: getToday(), tapePartNumber: '', finishedPartNumber: '', currentStockMeters: 0 });
            invDialogVisible.value = true;
        };

        const openEditInv = (row) => {
            Object.assign(invForm, row);
            invDialogVisible.value = true;
        };

        const saveInv = async () => {
            if (!invForm.tapePartNumber) { ElMessage.warning('带坯零件号为必填约束项！'); return; }
            invSaveLoading.value = true;
            try {
                const res = await axios.post('/api/v1/workshops/integration/inventory/save', invForm);
                ElMessage.success(res.data);
                invDialogVisible.value = false;
                loadInventory();
            } catch (error) {} finally { invSaveLoading.value = false; }
        };

        const deleteInv = async (id) => {
            try {
                await ElMessageBox.confirm('数据一旦删除将从物理磁盘抹除，确认执行？', '高危操作警告', { type: 'warning', confirmButtonText: '确认销毁', cancelButtonText: '取消' });
                await axios.delete(`/api/v1/workshops/integration/inventory/${id}`);
                ElMessage.success('数据已物理删除！');
                loadInventory();
            } catch (error) {
                if (error !== 'cancel') ElMessage.error('删除操作被系统拦截');
            }
        };

        // 监听侧边栏，如果进入库存页面，则自动刷新表格
        watch(activeMenu, (newVal) => {
            if (newVal === 'inventory') { loadInventory(); }
        });

        // ==========================================
        // 🛒 销售订单批量统一下达
        // ==========================================
        const orderHeader = reactive({ orderId: '', placerName: '', orderDate: getToday(), deliveryDate: '' });
        const orderItems = ref([{ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }]);
        const orderResult = ref('');

        const calcTotal = (row) => { row.totalLength = (row.metersPerRoll * row.rollCount).toFixed(2); };
        const addOrderItem = () => { orderItems.value.push({ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }); };
        const removeOrderItem = (index) => { if (orderItems.value.length > 1) orderItems.value.splice(index, 1); else ElMessage.warning('订单至少需要保留一行产品！'); };

        const submitOrder = async () => {
            if (!orderHeader.orderId) { ElMessage.error('请填写订单号！'); return; }
            loading.value = true; orderResult.value = '';
            const payload = orderItems.value.map(item => ({ ...orderHeader, ...item }));
            try {
                const res = await axios.post('/api/v1/workshops/orders/batch', payload);
                orderResult.value = res.data; ElMessage.success('订单批量下达成功！');
            } catch (error) {} finally { loading.value = false; }
        };

        // ==========================================
        // 📊 APS 大脑：全流水线联动排产推演引擎
        // ==========================================
        const adjForm = reactive({ orderId: '', manualWeavingChangeoverDays: null, manualOperatorRatio: null, manualCoexCapacity: null, manualStartDelayDays: null });
        const estForm = reactive({ orderId: '' });
        const estResult = ref(null);
        const calendarDate = ref(new Date());

        const submitAdvancedEstimation = async () => {
            const targetOrderId = activeMenu.value === 'estimation' ? estForm.orderId : adjForm.orderId;
            if (!targetOrderId) { ElMessage.warning('请输入待排产的订单号！'); return; }

            loading.value = true; estResult.value = null;
            try {
                const payload = activeMenu.value === 'estimation'
                    ? { orderId: targetOrderId }
                    : { ...adjForm, orderId: targetOrderId };

                const res = await axios.post('/api/v1/workshops/estimation/advanced-schedule', payload);
                estResult.value = res.data;

                if (res.data.overallStartDate) { calendarDate.value = new Date(res.data.overallStartDate); }
                ElMessage.success('APS 大脑重算完成，日历渲染就绪！');

                if(activeMenu.value !== 'estimation') { activeMenu.value = 'estimation'; estForm.orderId = targetOrderId; }
            } catch (error) {} finally { loading.value = false; }
        };

        const getCalendarTags = (dateStr) => {
            if (!estResult.value || !estResult.value.details) return [];
            const tags = [];
            estResult.value.details.forEach(item => {
                const isWeaving = item.weavingStart && dateStr >= item.weavingStart && dateStr <= item.weavingEnd;
                const isCoex = dateStr >= item.coexStart && dateStr <= item.coexEnd;

                if (isWeaving && isCoex) {
                    tags.push({ text: '织造+共挤', color: '#8b5cf6' });
                } else if (isWeaving) {
                    tags.push({ text: '织造期', color: '#3b82f6' });
                } else if (isCoex) {
                    tags.push({ text: '共挤期', color: '#f97316' });
                }
            });
            const uniqueTags = [];
            const seen = new Set();
            for (const tag of tags) {
                if (!seen.has(tag.text)) { seen.add(tag.text); uniqueTags.push(tag); }
            }
            return uniqueTags;
        };

        // 将所有用到的变量暴露给模板视图
        return {
            isLoggedIn, currentUser, activeMenu, loading, loginForm, handleLogin, handleLogout, handleMenuSelect,
            weavingForm, submitWeaving, coexForm, submitCoex, entryResult,
            invSearchKeyword, inventoryList, invLoading, invDialogVisible, invSaveLoading, invForm, loadInventory, openAddInv, openEditInv, saveInv, deleteInv,
            orderHeader, orderItems, orderResult, calcTotal, addOrderItem, removeOrderItem, submitOrder,
            estForm, adjForm, estResult, submitAdvancedEstimation, calendarDate, getCalendarTags
        };
    }
});

app.use(ElementPlus);
app.mount('#app');