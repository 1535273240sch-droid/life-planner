// 全局客户端交互模块
window.LifePlanner = {
    // 字符转义防止 XSS 注入 (P1-5 修复)
    escapeHtml: function(str) {
        if (str === null || str === undefined) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    },

    // 浮动通知 Toast
    toast: function(message, type = 'success') {
        let container = document.getElementById('toast-container');
        if (!container) {
            container = document.createElement('div');
            container.id = 'toast-container';
            container.className = 'fixed top-5 right-5 z-50 flex flex-col gap-2 max-w-sm w-full pointer-events-none px-4 sm:px-0';
            document.body.appendChild(container);
        }

        const toastEl = document.createElement('div');
        const isSuccess = type === 'success';
        const isError = type === 'error';
        const isWarning = type === 'warning';

        const borderColor = isSuccess ? 'border-[#00F5A0]' : (isError ? 'border-[#FF4D5E]' : 'border-[#F59E0B]');
        const iconColor = isSuccess ? 'text-[#00F5A0]' : (isError ? 'text-[#FF4D5E]' : 'text-[#F59E0B]');
        const icon = isSuccess ? '✓' : (isError ? '✕' : '⚠');

        const safeMessage = this.escapeHtml(message);
        toastEl.className = `pointer-events-auto transform transition-all duration-300 ease-out translate-y-[-10px] opacity-0 flex items-center gap-3 p-4 bg-[#12161c]/95 backdrop-blur-md border ${borderColor} rounded-xl shadow-2xl text-white text-sm`;
        toastEl.innerHTML = `
            <div class="flex-shrink-0 w-6 h-6 flex items-center justify-center rounded-full bg-white/5 font-bold ${iconColor}">${icon}</div>
            <div class="flex-1 font-medium">${safeMessage}</div>
        `;

        container.appendChild(toastEl);
        
        requestAnimationFrame(() => {
            toastEl.classList.remove('translate-y-[-10px]', 'opacity-0');
        });

        setTimeout(() => {
            toastEl.classList.add('opacity-0', 'translate-x-full');
            setTimeout(() => toastEl.remove(), 300);
        }, 3200);
    },

    // 封装通用 API 请求
    request: async function(url, options = {}) {
        const defaultHeaders = {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        };
        options.headers = { ...defaultHeaders, ...options.headers };

        try {
            const res = await fetch(url, options);
            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
                const errorMsg = data.detail || data.message || '请求失败，请稍后重试';
                throw new Error(errorMsg);
            }
            return data;
        } catch (err) {
            this.toast(err.message, 'error');
            throw err;
        }
    }
};

window.escapeHtml = window.LifePlanner.escapeHtml;

// 注册 PWA Service Worker (离线与安装支持)
if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
        navigator.serviceWorker.register('/static/sw.js')
            .then((reg) => {
                console.log('[LifePlanner PWA] Service Worker 已就绪, Scope:', reg.scope);
            })
            .catch((err) => {
                console.warn('[LifePlanner PWA] Service Worker 注册失败:', err);
            });
    });
}
