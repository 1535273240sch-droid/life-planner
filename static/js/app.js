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

    // 把后端错误响应解析成 { message, code }。
    // detail 可能是 {code, message} 对象 / 字符串 (404 等) / 校验错误数组 (422)。
    parseErrorDetail: function(data) {
        const FALLBACK = '请求失败，请稍后重试';
        const d = data && typeof data === 'object' ? data.detail : undefined;
        let message = '';
        let code = '';

        if (d && typeof d === 'object' && !Array.isArray(d)) {
            code = typeof d.code === 'string' ? d.code : '';
            if (typeof d.message === 'string' && d.message) message = d.message;
        } else if (typeof d === 'string' && d) {
            message = d;
        } else if (Array.isArray(d) && d.length > 0) {
            code = 'validation_error';
            const first = d[0];
            if (first && typeof first.msg === 'string' && first.msg) {
                message = first.msg;
            } else if (typeof first === 'string') {
                message = first;
            }
        }

        if (!message && data && typeof data === 'object') {
            if (typeof data.message === 'string' && data.message) message = data.message;
            else if (typeof data.error === 'string' && data.error) message = data.error;
        }
        if (!message && code && this.AI_ERROR_GUIDE[code]) message = this.AI_ERROR_GUIDE[code].tip;
        return { message: message || FALLBACK, code: code };
    },

    // 封装通用 API 请求。失败时抛出带 code / status 属性的 Error。
    request: async function(url, options = {}) {
        const defaultHeaders = {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        };
        options.headers = { ...defaultHeaders, ...options.headers };

        let res;
        try {
            res = await fetch(url, options);
        } catch (netErr) {
            // fetch 在网络层失败时抛 TypeError，没有任何 HTTP 响应
            const err = new Error('无法连接服务器，请检查网络或服务是否在运行');
            err.code = 'network_error';
            err.status = 0;
            this.toast(err.message, 'error');
            throw err;
        }

        const data = await res.json().catch(() => ({}));
        if (!res.ok) {
            const parsed = this.parseErrorDetail(data);
            const err = new Error(parsed.message);
            err.code = parsed.code;
            err.status = res.status;
            this.toast(err.message, 'error');
            throw err;
        }
        return data;
    },

    // AI 失败时按错误码给出的具体指引
    AI_ERROR_GUIDE: {
        ai_not_configured: {
            title: '大模型尚未配置',
            tip: '还没有配置大模型 API Key。请在项目根目录的 .env 文件里填写 AI_API_KEY，保存后重启服务。',
            level: 'red'
        },
        ai_unreachable: {
            title: '连不上大模型服务',
            tip: '连不上大模型服务，请检查网络或 AI_BASE_URL 是否正确，稍后重试。',
            level: 'amber'
        },
        ai_auth_failed: {
            title: 'API Key 无效或无权限',
            tip: 'API Key 无效或没有权限，请检查 .env 里的 AI_API_KEY。',
            level: 'red'
        },
        ai_rate_limited: {
            title: '请求过于频繁或额度已用完',
            tip: '请求太频繁或额度用完了，请稍后再试。',
            level: 'amber'
        },
        ai_bad_response: {
            title: '模型返回的内容无法使用',
            tip: '模型这次返回的内容无法使用，请重试一次。',
            level: 'amber'
        },
        network_error: {
            title: '无法连接服务器',
            tip: '无法连接服务器，请检查网络或服务是否在运行。',
            level: 'amber'
        }
    },

    // 在 containerEl 里渲染持久的 AI 错误卡片 (不会像 toast 一样消失)
    renderAIError: function(containerEl, err) {
        if (!containerEl) return;
        const code = err && err.code ? err.code : '';
        const guide = this.AI_ERROR_GUIDE[code];
        const isRed = guide ? guide.level === 'red' : false;
        const box = isRed
            ? 'bg-red-950/60 border-red-500/60 text-red-100'
            : 'bg-amber-950/50 border-amber-500/50 text-amber-100';
        const titleColor = isRed ? 'text-red-400' : 'text-amber-400';

        const title = guide ? guide.title : '请求没有成功';
        const guidance = guide ? guide.tip : ((err && err.message) || '请求失败，请稍后重试');
        // 服务端说明与指引不同时，作为补充原样展示
        const serverMsg = guide && err && err.message && err.message !== guide.tip ? err.message : '';

        containerEl.classList.remove('hidden');
        containerEl.innerHTML = `
            <div role="alert" class="p-4 rounded-2xl border ${box} text-xs space-y-2">
                <div class="font-bold flex items-center gap-2 text-sm ${titleColor}">
                    <span>${isRed ? '⛔' : '⚠️'}</span>
                    <span>${this.escapeHtml(title)}</span>
                </div>
                <p class="leading-relaxed">${this.escapeHtml(guidance)}</p>
                ${serverMsg ? `<p class="leading-relaxed opacity-80">服务端说明：${this.escapeHtml(serverMsg)}</p>` : ''}
                <p class="leading-relaxed opacity-90 pt-1 border-t border-white/10">本次没有生成任何内容，也没有使用模板代替，所以这里没有 AI 的答案。解决后可直接再点一次重试。</p>
            </div>
        `;
    },

    // 清空并隐藏错误卡片 / 危机面板等动态容器
    clearContainer: function(containerEl) {
        if (!containerEl) return;
        containerEl.innerHTML = '';
        containerEl.classList.add('hidden');
    },

    // 危机安全面板 (后端返回 is_crisis === true 时使用)
    renderCrisisPanel: function(containerEl, data) {
        if (!containerEl) return;
        const alt = data && data.alternative ? String(data.alternative) : '';
        containerEl.classList.remove('hidden');
        containerEl.innerHTML = `
            <div role="alert" class="p-4 bg-red-950/80 border border-red-500/70 rounded-2xl text-red-100 text-xs space-y-2.5 shadow-lg shadow-red-950/50">
                <div class="font-bold flex items-center gap-2 text-red-400 text-sm">
                    <span class="text-base">🆘</span>
                    <span>生命关怀与心理援助防线已启动</span>
                </div>
                <p class="leading-relaxed">检测到可能危及人身安全的表述。请立即停止自我加压，给自己一个安全温暖的保护空间。活着才有一切希望，没有任何一项目标值得以生命与健康为代价。请立即拨打以下 24 小时全国免费心理援助热线：</p>
                ${alt ? `<div class="whitespace-pre-wrap leading-relaxed bg-black/40 p-3 rounded-xl border border-white/10 text-red-50">${this.escapeHtml(alt)}</div>` : ''}
                <div class="bg-black/50 p-3 rounded-xl font-mono text-xs space-y-1.5 border border-white/5">
                    <div class="flex items-center justify-between"><span>• 全国希望生命救助热线:</span> <a href="tel:4001619995" class="text-[#00F5A0] font-bold underline">400-161-9995</a></div>
                    <div class="flex items-center justify-between"><span>• 北京心理危机研究与干预中心:</span> <a href="tel:01082951332" class="text-[#00F5A0] font-bold underline">010-82951332</a></div>
                    <div class="flex items-center justify-between"><span>• 共青团青少年心理咨询热线:</span> <a href="tel:12355" class="text-[#00F5A0] font-bold underline">12355</a></div>
                    <div class="flex items-center justify-between"><span>• 紧急危险请立即拨打:</span> <span class="text-red-400 font-bold">110 / 120</span></div>
                </div>
                <p class="leading-relaxed opacity-80">这是安全提示，不是 AI 生成的分析报告，本次没有生成复盘、规划或任务。</p>
            </div>
        `;
    },

    // 成功时的来源标识：仅当响应带 model 字段时才显示，不编造
    renderSourceNote: function(el, data) {
        if (!el) return;
        const model = data && typeof data.model === 'string' ? data.model.trim() : '';
        if (!model) {
            el.textContent = '';
            el.classList.add('hidden');
            return;
        }
        el.textContent = `✨ 由 ${model} 生成`;
        el.classList.remove('hidden');
    },

    // 历史记录里旧版本、来源未核实的记录标签 (返回 HTML 片段)
    legacyBadgeHtml: function(msg) {
        if (msg && msg.source === 'legacy_unverified') {
            return '<span class="text-[10px] px-1.5 py-0.5 rounded bg-slate-700/60 text-slate-300 border border-white/10 whitespace-nowrap">来源未核实（旧版本记录）</span>';
        }
        return '';
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
