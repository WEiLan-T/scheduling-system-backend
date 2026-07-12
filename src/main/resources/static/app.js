const { createApp, ref, reactive, onMounted } = Vue;
const { ElMessage } = ElementPlus;

const app = createApp({
    setup() {
        // 工具函数：获取格式化的今日日期
        const getToday = () => {
            const d = new Date();
            return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        };

        // 全局状态
        const isLoggedIn = ref(false);
        const currentUser = ref('');
        const activeMenu = ref('weaving');
        const loading = ref(false);

        // 🛡️ Axios 零信任拦截器
        axios.interceptors.request.use(config => {
            const token = localStorage.getItem('jwt_token');
            if (token) config.headers['Authorization'] = 'Bearer ' + token;
            return config;
        });

        axios.interceptors.response.use(response => response, error => {
            if (error.response && error.response.status === 403) {
                ElMessage({ message: '⛔ 安全中心警告：您当前的角色权限不足，禁止操作此业务！', type: 'error', duration: 4000 });
            } else if (error.response && error.response.status === 401) {
                ElMessage.warning('安保凭证已过期，请重新登入！');
                handleLogout();
            } else {
                ElMessage.error('服务器内部通讯故障，请检查后端是否报错！');
            }
            return Promise.reject(error);
        });

        // 自动恢复会话
        onMounted(() => {
            const token = localStorage.getItem('jwt_token');
            const user = localStorage.getItem('current_user');
            if (token && user) { isLoggedIn.value = true; currentUser.value = user; }
        });

        // 🔒 登录认证
        const loginForm = reactive({ username: '', password: '' });
        const handleLogin = async () => {
            if (!loginForm.username || !loginForm.password) return;
            loading.value = true;
            try {
                const res = await axios.post('/api/v1/auth/login', loginForm);
                if (res.data && res.data.includes('eyJ')) {
                    const token = res.data.substring(res.data.indexOf('eyJ')).trim();
                    localStorage.setItem('jwt_token', token);
                    localStorage.setItem('current_user', loginForm.username);
                    isLoggedIn.value = true; currentUser.value = loginForm.username;
                    ElMessage.success('身份核验通过，欢迎进入系统！');
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
            entryResult.value = ''; estResult.value = ''; orderResult.value = ''; invResult.value = '';
        };

        // 🧶 织造聚合逻辑
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
                entryResult.value = res.data; ElMessage.success('织造数据归档成功，库存已同步！');
            } catch (error) {} finally { loading.value = false; }
        };

        // 🗜️ 共挤聚合逻辑
        const coexForm = reactive({
            entryDate: getToday(), lineId: 'g1', finishedPartNumber: 'U35002', workshopId: '共挤1车间',
            caliberLimit: '350', lineStatus: '在产', capacityPerDay: 260, isDataNormal: true,
            tapeDemandQty: 260, tapePartNumber: '9D2002', remarks: ''
        });
        const submitCoex = async () => {
            loading.value = true; entryResult.value = '';
            try {
                const res = await axios.post('/api/v1/workshops/integration/coextrusion/logs', coexForm);
                entryResult.value = res.data; ElMessage.success('共挤数据归档成功，已扣减带坯！');
            } catch (error) {} finally { loading.value = false; }
        };

        // 📦 库存调账逻辑
        const invForm = reactive({ entryDate: getToday(), tapePartNumber: '', adjustMeters: 0, remarks: '人工盘点调整' });
        const invResult = ref('');
        const submitInventory = async () => {
            loading.value = true; invResult.value = '';
            try {
                const res = await axios.post('/api/v1/workshops/integration/inventory/adjust', invForm);
                invResult.value = res.data; ElMessage.success('库存调账指令执行完毕！');
            } catch (error) {} finally { loading.value = false; }
        };

        // 🛒 订单多行录入逻辑
        const orderHeader = reactive({ orderId: '', placerName: '', orderDate: getToday(), deliveryDate: '' });
        const orderItems = ref([{ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }]);
        const orderResult = ref('');

        const calcTotal = (row) => { row.totalLength = (row.metersPerRoll * row.rollCount).toFixed(2); };
        const addOrderItem = () => { orderItems.value.push({ finishedPartNumber: '', modelSpec: '', material: '', caliber: '', metersPerRoll: 0, rollCount: 0, totalLength: 0, remarks: '' }); };
        const removeOrderItem = (index) => { if (orderItems.value.length > 1) orderItems.value.splice(index, 1); else ElMessage.warning('订单至少需要保留一行产品！'); };

        const submitOrder = async () => {
            if (!orderHeader.orderId) { ElMessage.error('请填写订单号！'); return; }
            loading.value = true; orderResult.value = '';
            // 组装表头和明细给后端
            const payload = orderItems.value.map(item => ({ ...orderHeader, ...item }));
            try {
                const res = await axios.post('/api/v1/workshops/orders/batch', payload);
                orderResult.value = res.data; ElMessage.success('订单批量下达成功！');
            } catch (error) {} finally { loading.value = false; }
        };

        // 📊 排产推演逻辑
        const estForm = reactive({ machineId: 'z12', targetQty: 1000, customCapacity: null });
        const estResult = ref('');
        const submitEstimation = async () => {
            loading.value = true; estResult.value = '';
            try {
                let url = `/api/v1/workshops/estimation/dynamic-completion-time?machineId=${estForm.machineId}&targetQty=${estForm.targetQty}`;
                if (estForm.customCapacity) url += `&customCapacity=${estForm.customCapacity}`;
                const res = await axios.get(url);
                estResult.value = res.data; ElMessage.success('APS 大脑推演完毕！');
            } catch (error) {} finally { loading.value = false; }
        };

        return {
            isLoggedIn, currentUser, activeMenu, loading, loginForm, handleLogin, handleLogout, handleMenuSelect,
            weavingForm, submitWeaving, coexForm, submitCoex, entryResult,
            invForm, invResult, submitInventory,
            orderHeader, orderItems, orderResult, calcTotal, addOrderItem, removeOrderItem, submitOrder,
            estForm, estResult, submitEstimation
        };
    }
});

app.use(ElementPlus);
app.mount('#app');