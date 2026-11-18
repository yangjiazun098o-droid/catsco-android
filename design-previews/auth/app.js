import { mountAuthFlow } from './auth-flow.js';

const byId = (id) => document.getElementById(id);
const phone = byId('phone');
const form = byId('auth-form');
const inputs = Object.fromEntries(['account', 'code', 'password', 'confirm', 'invite'].map((name) => [name, byId(name)]));
const fieldNames = ['account', 'code', 'password', 'confirm'];
const labels = { login: '登录', register: '注册', reset: '重置密码' };
const loadingLabels = { login: '登录中…', register: '注册中…', reset: '重置中…' };
const flow = mountAuthFlow(byId('flow-background'));
let mode = 'login';
let submitting = false;
let sendingCode = false;
const codeDeadlines = new Map();
let operation = 0;
let keyboardVisible = false;
let pendingTimer = 0;
let codeTimer = 0;

const eye = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></svg>';
const hiddenEye = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 3 18 18M10 6.2a11 11 0 0 1 2-.2c6.5 0 10 6 10 6a18 18 0 0 1-3.1 3.6M6.2 6.4A18 18 0 0 0 2 12s3.5 6 10 6a12 12 0 0 0 4-.7M10 10a3 3 0 0 0 4 4"/></svg>';

function errorFor(name) {
  const value = inputs[name].value.trim();
  if (name === 'account') {
    if (!value) return mode === 'login' ? '请输入用户名或邮箱' : '请输入邮箱地址';
    if (mode !== 'login' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return '请输入有效的邮箱地址';
  }
  if (name === 'code' && mode !== 'login' && !value) return '请输入邮箱验证码';
  if (name === 'password') {
    if (!inputs.password.value) return mode === 'reset' ? '请输入新密码' : '请输入密码';
    if (mode !== 'login' && inputs.password.value.length < 6) return '密码至少需要6位';
  }
  if (name === 'confirm' && mode === 'reset') {
    if (!inputs.confirm.value) return '请再次输入新密码';
    if (inputs.confirm.value !== inputs.password.value) return '两次输入的密码不一致';
  }
  return '';
}

function setFieldError(name, message) {
  const output = byId(`${name}-error`);
  output.textContent = message;
  output.hidden = !message;
  inputs[name].setAttribute('aria-invalid', String(Boolean(message)));
  if (message) inputs[name].setAttribute('aria-describedby', output.id);
  else inputs[name].removeAttribute('aria-describedby');
}

function feedback(message = '', tone = 'neutral') {
  const output = byId('form-feedback');
  output.textContent = message;
  output.dataset.tone = tone;
  output.hidden = !message;
}

function updateCodeButton() {
  const key = `${mode}:${inputs.account.value.trim().toLowerCase()}`;
  const remaining = Math.max(0, Math.ceil(((codeDeadlines.get(key) || 0) - Date.now()) / 1000));
  const button = byId('send-code');
  button.disabled = submitting || sendingCode || remaining > 0;
  button.textContent = sendingCode ? '发送中…' : remaining > 0 ? `${remaining}秒后重发` : '发送验证码';
  button.setAttribute('aria-busy', String(sendingCode));
  if (remaining > 0 && !codeTimer) codeTimer = setInterval(updateCodeButton, 1000);
  if (remaining === 0 && codeTimer) { clearInterval(codeTimer); codeTimer = 0; }
}

function updateControls() {
  Object.values(inputs).forEach((input) => { input.disabled = submitting; });
  document.querySelectorAll('.password-toggle').forEach((button) => { button.disabled = submitting; });
  document.querySelectorAll('[data-page]').forEach((button) => { button.disabled = submitting; });
  byId('submit').disabled = submitting;
  byId('submit').setAttribute('aria-busy', String(submitting));
  byId('submit-label').textContent = submitting ? loadingLabels[mode] : labels[mode];
  updateCodeButton();
}

function hidePasswords() {
  document.querySelectorAll('.password-toggle').forEach((button) => {
    inputs[button.dataset.input].type = 'password';
    button.innerHTML = eye;
    button.setAttribute('aria-pressed', 'false');
    button.setAttribute('aria-label', button.dataset.input === 'confirm' ? '显示确认密码' : '显示密码');
  });
}

function setMode(nextMode) {
  if (!labels[nextMode]) return;
  operation += 1;
  clearTimeout(pendingTimer);
  submitting = false;
  sendingCode = false;
  mode = nextMode;
  fieldNames.forEach((name) => setFieldError(name, ''));
  inputs.code.value = '';
  inputs.password.value = '';
  inputs.confirm.value = '';
  feedback();
  byId('code-hint').hidden = true;
  byId('code-group').hidden = mode === 'login';
  byId('confirm-group').hidden = mode !== 'reset';
  byId('invite-group').hidden = mode !== 'register';
  byId('reset-intro').hidden = mode !== 'reset';
  for (const name of Object.keys(labels)) byId(`${name}-footer`).hidden = name !== mode;
  document.querySelector('.auth-card').setAttribute('aria-label', labels[mode]);
  document.querySelectorAll('.preview-toolbar [data-page]').forEach((button) => {
    button.setAttribute('aria-pressed', String(button.dataset.page === mode));
  });
  const accountLabel = mode === 'login' ? '用户名或邮箱' : '邮箱地址';
  inputs.account.placeholder = accountLabel;
  inputs.account.setAttribute('aria-label', accountLabel);
  inputs.account.type = mode === 'login' ? 'text' : 'email';
  inputs.account.autocomplete = mode === 'login' ? 'username' : 'email';
  const passwordLabel = mode === 'login' ? '密码' : mode === 'reset' ? '新密码（至少6位）' : '设置密码（至少6位）';
  inputs.password.placeholder = passwordLabel;
  inputs.password.setAttribute('aria-label', passwordLabel);
  inputs.password.autocomplete = mode === 'login' ? 'current-password' : 'new-password';
  inputs.password.enterKeyHint = mode === 'reset' ? 'next' : 'go';
  hidePasswords();
  updateControls();
  byId('auth-scroll').scrollTop = 0;
}

function updateFlow() {
  flow.setPaused(keyboardVisible || Boolean(document.activeElement?.matches('input')));
}

function setKeyboard(visible) {
  keyboardVisible = visible;
  phone.classList.toggle('keyboard-open', visible);
  byId('mock-keyboard').hidden = !visible;
  byId('keyboard-toggle').setAttribute('aria-pressed', String(visible));
  updateFlow();
  if (visible) {
    const focused = form.contains(document.activeElement) && document.activeElement.matches('input') ? document.activeElement : inputs.account;
    focused.focus({ preventScroll: true });
    focused.scrollIntoView({ block: 'nearest' });
  }
}

function focusNext() {
  const visibleInputs = Object.values(inputs).filter((input) => !input.closest('[hidden]'));
  const index = visibleInputs.indexOf(document.activeElement);
  const next = visibleInputs[index + 1];
  if (next) {
    next.focus({ preventScroll: true });
    next.scrollIntoView({ block: 'nearest' });
  } else form.requestSubmit();
}

document.querySelectorAll('[data-page]').forEach((button) => {
  button.addEventListener('click', () => { setKeyboard(false); setMode(button.dataset.page); });
});

document.querySelectorAll('.password-toggle').forEach((button) => {
  button.addEventListener('click', () => {
    const input = inputs[button.dataset.input];
    const show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    button.innerHTML = show ? hiddenEye : eye;
    button.setAttribute('aria-pressed', String(show));
    button.setAttribute('aria-label', `${show ? '隐藏' : '显示'}${button.dataset.input === 'confirm' ? '确认密码' : '密码'}`);
  });
});

fieldNames.forEach((name) => {
  inputs[name].addEventListener('blur', () => {
    if (inputs[name].value) setFieldError(name, errorFor(name));
  });
  inputs[name].addEventListener('input', () => {
    if (inputs[name].getAttribute('aria-invalid') === 'true') setFieldError(name, errorFor(name));
    feedback();
  });
});
inputs.invite.addEventListener('input', () => { inputs.invite.value = inputs.invite.value.toUpperCase(); });
inputs.account.addEventListener('input', () => {
  byId('code-hint').hidden = true;
  updateCodeButton();
});

form.addEventListener('focusin', (event) => {
  if (!event.target.matches('input')) return;
  updateFlow();
  byId('keyboard-hint').textContent = event.target.getAttribute('aria-label');
  byId('keyboard-next').textContent = event.target.enterKeyHint === 'go' ? labels[mode] : '下一项';
});
form.addEventListener('focusout', () => { requestAnimationFrame(updateFlow); });
form.addEventListener('keydown', (event) => {
  if (event.key !== 'Enter' || event.isComposing || !event.target.matches('input')) return;
  event.preventDefault();
  if (['account', 'code'].includes(event.target.id) || (event.target.id === 'password' && mode === 'reset')) focusNext();
  else form.requestSubmit();
});

byId('send-code').addEventListener('click', () => {
  if (byId('send-code').disabled) return;
  const error = errorFor('account');
  setFieldError('account', error);
  if (error) { inputs.account.focus(); return; }
  sendingCode = true;
  const requestOperation = operation;
  const email = inputs.account.value.trim();
  updateCodeButton();
  pendingTimer = setTimeout(() => {
    if (operation !== requestOperation) return;
    sendingCode = false;
    if (inputs.account.value.trim() === email) {
      codeDeadlines.set(`${mode}:${email.toLowerCase()}`, Date.now() + 60000);
      byId('code-hint').textContent = '验证码发送已演示，请输入任意验证码体验下一步。';
      byId('code-hint').hidden = false;
      inputs.code.focus({ preventScroll: true });
      inputs.code.scrollIntoView({ block: 'nearest' });
    }
    updateCodeButton();
  }, 700);
});

form.addEventListener('submit', (event) => {
  event.preventDefault();
  if (submitting || sendingCode) return;
  const visibleNames = fieldNames.filter((name) => !inputs[name].closest('[hidden]'));
  const invalid = visibleNames.filter((name) => {
    const message = errorFor(name);
    setFieldError(name, message);
    return Boolean(message);
  });
  if (invalid.length) { inputs[invalid[0]].focus(); inputs[invalid[0]].scrollIntoView({ block: 'nearest' }); return; }
  document.activeElement?.blur();
  setKeyboard(false);
  submitting = true;
  updateControls();
  const requestOperation = operation;
  pendingTimer = setTimeout(() => {
    if (requestOperation !== operation) return;
    submitting = false;
    updateControls();
    feedback(mode === 'reset' ? '重置密码交互已演示，可返回登录继续体验。' : `${labels[mode]}交互已演示，预览使用示例响应。`);
    byId('form-feedback').scrollIntoView({ block: 'nearest' });
  }, 1100);
});

byId('keyboard-toggle').addEventListener('click', () => setKeyboard(!keyboardVisible));
byId('keyboard-close').addEventListener('click', () => { document.activeElement?.blur(); setKeyboard(false); });
byId('keyboard-next').addEventListener('pointerdown', (event) => event.preventDefault());
byId('keyboard-next').addEventListener('click', focusNext);
byId('theme-toggle').addEventListener('click', () => {
  const dark = document.documentElement.dataset.theme !== 'dark';
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  byId('theme-toggle').textContent = dark ? '切换浅色' : '切换深色';
});

const serverDialog = byId('server-dialog');
function closeServer() {
  serverDialog.hidden = true;
  byId('auth-scroll').inert = false;
  byId('server-open').focus();
}
byId('server-open').addEventListener('click', () => {
  setKeyboard(false);
  serverDialog.hidden = false;
  byId('auth-scroll').inert = true;
  byId('server-address').focus();
});
serverDialog.addEventListener('click', (event) => { if (event.target === serverDialog) closeServer(); });
serverDialog.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') { event.preventDefault(); closeServer(); }
  if (event.key === 'Tab') {
    const first = byId('server-address');
    const last = byId('server-form').querySelector('button');
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }
});
byId('server-form').addEventListener('submit', (event) => {
  event.preventDefault();
  let valid = false;
  try {
    const url = new URL(byId('server-address').value.trim());
    valid = url.protocol === 'https:' && !url.username && !url.password && Boolean(url.hostname);
  } catch { valid = false; }
  byId('server-error').hidden = valid;
  byId('server-error').textContent = valid ? '' : '请输入有效的 HTTPS 服务器地址';
  if (valid) closeServer();
});

function fitPreview() {
  const stage = document.querySelector('.preview-stage');
  const scale = Math.min(1, (stage.clientHeight - 44) / 864, (stage.clientWidth - 32) / 405);
  document.documentElement.style.setProperty('--preview-scale', String(Math.max(.25, scale)));
}
new ResizeObserver(fitPreview).observe(document.querySelector('.preview-stage'));
setMode(new URLSearchParams(location.search).get('page') || 'login');
fitPreview();
