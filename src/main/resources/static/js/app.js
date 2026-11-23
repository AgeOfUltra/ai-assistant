// ===== API Base =====
const API = '';

// ===== State =====
let conversationId = null;
let isStreaming = false;

// ===== Utility Functions =====
function formatTime() {
    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function scrollToBottom(container) {
    container.scrollTop = container.scrollHeight;
}

// ===== Connection Status =====
async function checkConnection() {
    const dot = document.getElementById('statusDot');
    const text = document.getElementById('statusText');
    try {
        const res = await fetch(`${API}/api/chat`);
        if (res.status === 405 || res.status === 400 || res.status === 200) {
            dot.className = 'status-dot connected';
            text.textContent = 'Connected';
        } else {
            throw new Error('Not OK');
        }
    } catch {
        dot.className = 'status-dot error';
        text.textContent = 'Disconnected';
    }
}

// ===== Chat Functions =====
function addMessage(text, isUser = false, sources = null) {
    const container = document.getElementById('chatMessages');
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${isUser ? 'user-message' : 'bot-message'}`;

    let sourcesHtml = '';
    if (sources && sources.length > 0) {
        sourcesHtml = '<div class="message-sources">';
        sources.forEach(s => {
            sourcesHtml += `<span class="source-item">📄 ${s.file || s.name || 'Unknown'}</span>`;
        });
        sourcesHtml += '</div>';
    }

    messageDiv.innerHTML = `
        <div class="message-avatar">${isUser ? 'You' : 'AI'}</div>
        <div class="message-content">
            <div class="message-text">${text}</div>
            ${sourcesHtml}
            <div class="message-time">${formatTime()}</div>
        </div>
    `;

    container.appendChild(messageDiv);
    scrollToBottom(container);
    return messageDiv;
}

function addTypingIndicator() {
    const container = document.getElementById('chatMessages');
    const div = document.createElement('div');
    div.className = 'message bot-message';
    div.id = 'typingIndicator';
    div.innerHTML = `
        <div class="message-avatar">AI</div>
        <div class="message-content">
            <div class="typing-indicator">
                <span></span><span></span><span></span>
            </div>
        </div>
    `;
    container.appendChild(div);
    scrollToBottom(container);
}

function removeTypingIndicator() {
    const el = document.getElementById('typingIndicator');
    if (el) el.remove();
}

async function sendMessage(message) {
    if (!message.trim() || isStreaming) return;

    const input = document.getElementById('chatInput');
    const sendBtn = document.getElementById('sendBtn');

    addMessage(message.trim(), true);
    input.value = '';
    input.style.height = 'auto';
    isStreaming = true;
    sendBtn.disabled = true;

    addTypingIndicator();

    try {
        const res = await fetch(`${API}/api/chat`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                message: message.trim(),
                conversationId: conversationId
            })
        });

        removeTypingIndicator();

        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const data = await res.json();
        conversationId = data.conversationId || conversationId;
        addMessage(data.response || 'No response', false, data.sources);
    } catch (err) {
        removeTypingIndicator();
        addMessage(`⚠️ Error: ${err.message}. Make sure the backend is running.`, false);
    } finally {
        isStreaming = false;
        sendBtn.disabled = false;
        input.focus();
    }
}

function setupChat() {
    const input = document.getElementById('chatInput');
    const sendBtn = document.getElementById('sendBtn');

    // Auto-resize textarea
    input.addEventListener('input', () => {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 120) + 'px';
    });

    // Send on Enter (Shift+Enter for newline)
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage(input.value);
        }
    });

    sendBtn.addEventListener('click', () => sendMessage(input.value));

    // Hint buttons
    document.querySelectorAll('.hint-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            input.value = btn.dataset.query;
            sendMessage(btn.dataset.query);
        });
    });
}

// ===== Initialize =====
document.addEventListener('DOMContentLoaded', () => {
    setupChat();
    checkConnection();
});
