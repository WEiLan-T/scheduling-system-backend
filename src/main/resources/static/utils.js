// 公共工具函数库
const SchedulingUtils = {
    // 日期格式化
    formatDate(dateStr) {
        if (!dateStr) return '';
        const d = new Date(dateStr);
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    },
    
    // 日期时间格式化
    formatDateTime(dateStr) {
        if (!dateStr) return '';
        return dateStr.replace('T', ' ').substring(0, 16);
    },
    
    // 获取今天日期字符串
    getToday() {
        const d = new Date();
        return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    },
    
    // 清理ID中的特殊字符
    cleanId(id) {
        return String(id).replace(/[#线]/g, '').trim();
    },
    
    // 获取状态CSS类名
    getStatusClass(status) {
        if (!status) return 'status-other';
        if (status.includes('产')) return 'status-producing';
        if (status.includes('闲')) return 'status-idle';
        if (status.includes('停')) return 'status-stopped';
        if (status.includes('修')) return 'status-maintenance';
        return 'status-other';
    },
    
    // 安全解析数字
    parseNumber(val) {
        if (val == null || val === '') return 0;
        const n = Number(val);
        return isNaN(n) ? 0 : n;
    },
    
    // 安全解析浮点数
    parseFloat2(val) {
        if (val == null || val === '') return 0.0;
        const n = parseFloat(val);
        return isNaN(n) ? 0.0 : n;
    }
};
