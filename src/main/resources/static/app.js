const { createApp, ref, reactive, onMounted } = Vue;
const { ElMessage } = ElementPlus;

const app = createApp({
    setup() {
        // --- 1. 全局状态 ---
        const isLoggedIn = ref(false);
        const currentUser = ref('');
        const activeMenu = ref('entry');
        const loading = ref(false);

        // --- 2. Axios 拦截器 (核心：自动携带 JWT 通行证) ---
        axios.interceptors.request.use(config => {
            const token = localStorage.getItem('jwt_token');
            if (token) {
                config.headers['Authorization'] = 'Bearer ' + token;
            }
            return config;
        });

        // 统一处理后端的 403 权限拦截
        axios.interceptors.response.use(response => response, error => {
            if (error.response && error.response.status === 403) {
                ElMessage.error('❌ 安全拦截：您当前的角色权限不足，禁止执行此操作！');
            } else if (error.response && error.response.status === 401) {
                ElMessage.warning('登录已过期，请重新登录！');
                handleLogout();
            } else {
                ElMessage.error('网络请求失败，请检查后端报错');
            }
            return Promise.reject(error);
        });

        // 页面刷新时，检查是否已经登录过
        onMounted(() => {
            const token = localStorage.getItem('jwt_token');
            const user = localStorage.getItem('current_user');
            if (token && user) {
                isLoggedIn.value = true;
                currentUser.value = user;
            }
        });

        // --- 3. 登录与注销逻辑 ---
        const loginForm = reactive({ username: 'PLANNER_01', password: 'MySecretPassword123' });

        const handleLogin = async () => {
            loading.value = true;
            try {
                // 因为我们在同一个项目中，直接调用 /api... 即可，不需要写 http://localhost:8080
                const res = await axios.post('/api/v1/auth/login', loginForm);
                const tokenStr = res.data;

                // 智能提取后端返回的 eyJ 开头的 JWT Token
                if (tokenStr && tokenStr.includes('eyJ')) {
                    const token = tokenStr.substring(tokenStr.indexOf('eyJ')).trim();
                    localStorage.setItem('jwt_token', token);
                    localStorage.setItem('current_user', loginForm.username);

                    isLoggedIn.value = true;
                    currentUser.value = loginForm.username;
                    ElMessage.success('安全通道建立，登录成功！');
                } else {
                    ElMessage.error('登录异常：未收到合法令牌');
                }
            } catch (error) {
                console.error(error);
            } finally {
                loading.value = false;
            }
        };

        const handleLogout = () => {
            localStorage.removeItem('jwt_token');
            localStorage.removeItem('current_user');
            isLoggedIn.value = false;
            currentUser.value = '';
            ElMessage.info('已安全退出系统');
        };

        const handleMenuSelect = (index) => { activeMenu.value = index; };

        // --- 4. 台账录入逻辑 ---
        const entryForm = reactive({
            workerId: 'WORKER_001',
            machineId: 'z1',
            qty: 150,
            tapePartNumber: '123e4567-e89b-12d3-a456-426614174000'
        });
        const entryResult = ref('');

        const submitEntry = async () => {
            loading.value = true;
            entryResult.value = '';
            try {
                const data = {
                    workerId: entryForm.workerId,
                    machineId: entryForm.machineId,
                    inputType: 'PRODUCTION',
                    qty: entryForm.qty,
                    tapePartNumber: entryForm.tapePartNumber
                };
                const res = await axios.post('/api/v1/workshops/integration/daily-logs', data);
                entryResult.value = res.data;
                ElMessage.success('底层数据写入成功');
            } catch (error) {
                // 错误已由拦截器统一提示
            } finally {
                loading.value = false;
            }
        };

        // --- 5. 排产估算逻辑 ---
        const estForm = reactive({ machineId: 'z12', targetQty: 1000, customCapacity: null });
        const estResult = ref('');

        const submitEstimation = async () => {
            loading.value = true;
            estResult.value = '';
            try {
                let url = `/api/v1/workshops/estimation/dynamic-completion-time?machineId=${estForm.machineId}&targetQty=${estForm.targetQty}`;
                if (estForm.customCapacity) {
                    url += `&customCapacity=${estForm.customCapacity}`;
                }
                const res = await axios.get(url);
                estResult.value = res.data;
                ElMessage.success('推演模型计算完毕');
            } catch (error) {
                // 错误已由拦截器统一提示
            } finally {
                loading.value = false;
            }
        };

        return {
            isLoggedIn, currentUser, activeMenu, loading,
            loginForm, handleLogin, handleLogout, handleMenuSelect,
            entryForm, entryResult, submitEntry,
            estForm, estResult, submitEstimation
        };
    }
});

app.use(ElementPlus);
app.mount('#app');