const $ = id => document.getElementById(id);
let file = null, previewUrl = null, pages = [], current = 0, busy = false, translating = false;
let fileVersion = 0, textVersion = 0, toastTimer;
const toast = message => { $('toast').textContent = message; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 6500); };
function switchView(view) {
  document.querySelectorAll('.tab').forEach(button => button.classList.toggle('active', button.dataset.view === view));
  ['text', 'translation', 'preview'].forEach(name => $(name + 'View').hidden = name !== view);
}
document.querySelectorAll('.tab').forEach(button => button.onclick = () => switchView(button.dataset.view));
function updateControls() {
  $('recognize').disabled = !file || busy || translating;
  $('translate').disabled = busy || translating || !$('sourceText').value.trim();
  $('fileInput').disabled = busy || translating;
  $('dropzone').disabled = busy || translating;
  $('removeFile').disabled = busy || translating;
  $('pageSelect').disabled = busy || translating;
  $('sourceText').readOnly = busy || translating;
  $('charCount').textContent = $('sourceText').value.length.toLocaleString() + ' 字符';
  $('emptyArt').hidden = !!$('sourceText').value;
  const hasEnglish = /[a-zA-Z]{2,}/.test($('sourceText').value);
  $('translationHint').textContent = hasEnglish ? '检测到英文，可翻译当前页' : '可将当前页文字翻译为中文';
}
function clearResults() {
  pages = []; current = 0; textVersion++;
  $('sourceText').value = ''; $('translatedText').value = '';
  $('pageSelect').replaceChildren(new Option('第 1 页', '0'));
  $('resultBadge').textContent = '等待识别'; $('resultBadge').className = 'badge';
  $('methodLabel').textContent = '支持中英文混合识别'; $('confidenceNote').hidden = true;
  updateControls();
}
function setFile(next) {
  if (busy || translating) return;
  if (next && !/\.(png|jpe?g|webp|bmp|tiff?|pdf)$/i.test(next.name)) return toast('请选择支持的图片或 PDF 文件。');
  if (next && next.size > 40 * 1024 * 1024) return toast('文件不能超过 40 MB。');
  file = next; fileVersion++; clearResults();
  if (previewUrl) URL.revokeObjectURL(previewUrl);
  $('imagePreview').hidden = true; $('imagePreview').removeAttribute('src');
  $('pdfPreview').hidden = true; $('pdfPreview').removeAttribute('src');
  $('previewEmpty').hidden = !!file;
  $('fileCard').hidden = !file;
  $('fileInput').value = '';
  if (file) {
    $('fileName').textContent = file.name; $('fileSize').textContent = (file.size / 1024 / 1024).toFixed(2) + ' MB';
    const pdf = /\.pdf$/i.test(file.name);
    $('pageRange').disabled = !pdf; $('mode').disabled = !pdf;
    previewUrl = URL.createObjectURL(file);
    const preview = $(pdf ? 'pdfPreview' : 'imagePreview'); preview.src = previewUrl; preview.hidden = false;
  }
  updateControls();
}
$('dropzone').onclick = () => $('fileInput').click();
$('fileInput').onchange = e => setFile(e.target.files[0]);
$('removeFile').onclick = () => setFile(null);
['dragenter', 'dragover'].forEach(name => $('dropzone').addEventListener(name, e => { e.preventDefault(); $('dropzone').classList.add('dragover'); }));
['dragleave', 'drop'].forEach(name => $('dropzone').addEventListener(name, e => { e.preventDefault(); $('dropzone').classList.remove('dragover'); }));
$('dropzone').addEventListener('drop', e => { if (e.dataTransfer.files.length > 1) toast('每次处理一个文件，已选择第一个。'); if (e.dataTransfer.files[0]) setFile(e.dataTransfer.files[0]); });
window.addEventListener('dragover', e => e.preventDefault()); window.addEventListener('drop', e => e.preventDefault());
async function request(url, options) {
  const response = await fetch(url, options);
  let data;
  try { data = await response.json(); } catch { throw new Error('服务返回异常，请确认本地服务正在运行。'); }
  if (!response.ok) throw new Error(typeof data.detail === 'string' ? data.detail : '请求无效，文字最多支持 60000 字符。');
  return data;
}
function savePage() { if (pages[current]) { pages[current].text = $('sourceText').value; pages[current].translation = $('translatedText').value; } }
function showPage(index) {
  current = index;
  const page = pages[index];
  $('sourceText').value = page.text; $('translatedText').value = page.translation || '';
  $('methodLabel').textContent = page.method === 'text' ? 'PDF 原生文字提取' : 'OCR 文字识别';
  $('confidenceNote').hidden = !page.low_confidence;
  $('confidenceNote').textContent = `有 ${page.low_confidence} 行识别置信度较低，建议对照文件预览校对。`;
  textVersion++; updateControls();
}
$('pageSelect').onchange = () => { savePage(); showPage(Number($('pageSelect').value)); };
$('sourceText').oninput = () => { textVersion++; $('translatedText').value = ''; if (pages[current]) pages[current].translation = ''; updateControls(); };
$('recognize').onclick = async () => {
  if (!file || busy) return;
  busy = true; updateControls(); $('progress').hidden = false;
  $('progressText').textContent = '正在识别，首次加载模型可能需要几分钟…';
  const form = new FormData(); form.append('file', file); form.append('mode', $('mode').value); form.append('page_range', $('pageRange').value);
  try {
    const result = await request('/api/recognize', {method: 'POST', body: form});
    pages = result.pages; current = 0;
    $('pageSelect').replaceChildren(...pages.map((page, i) => new Option('第 ' + page.number + ' 页', i)));
    if (pages.length) showPage(0);
    $('resultBadge').textContent = `${pages.length} 页 · ${result.elapsed} 秒`; $('resultBadge').className = 'badge ready';
    switchView('text');
    toast(pages.some(p => p.text) ? '识别完成，原文可直接编辑和导出。' : '未发现可识别的文字，请尝试更清晰的图片或全页 OCR。');
  } catch (error) { toast(error.message); }
  finally { busy = false; $('progress').hidden = true; updateControls(); }
};
$('translate').onclick = async () => {
  if (translating || busy || !$('sourceText').value.trim()) return;
  const version = textVersion, original = $('sourceText').value;
  translating = true; updateControls(); switchView('translation');
  $('translate').querySelector('span').textContent = '正在翻译…';
  try {
    const result = await request('/api/translate', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({text:original, provider:$('provider').value})});
    if (version === textVersion) { $('translatedText').value = result.text; savePage(); toast('翻译完成。'); }
  } catch (error) { toast(error.message); }
  finally { translating = false; $('translate').querySelector('span').textContent = '翻译为中文'; updateControls(); }
};
async function copy(id) {
  if (!$(id).value) return toast('暂无可复制的文字。');
  try { await navigator.clipboard.writeText($(id).value); toast('已复制到剪贴板。'); }
  catch { $(id).focus(); $(id).select(); toast('请按 Ctrl+C 复制选中的文字。'); }
}
function download(translation) {
  savePage();
  const parts = pages.length ? pages.map(p => ({number:p.number, text:translation ? p.translation || '' : p.text})).filter(p => p.text.trim()) : [{number:1, text:translation ? $('translatedText').value : $('sourceText').value}];
  if (!parts.some(p => p.text.trim())) return toast('暂无可导出的文字。');
  const content = parts.map(p => pages.length > 1 ? `—— 第 ${p.number} 页 ——\n\n${p.text}` : p.text).join('\n\n');
  const url = URL.createObjectURL(new Blob(['\ufeff', content], {type:'text/plain;charset=utf-8'}));
  const a = document.createElement('a'); a.href = url; a.download = (file?.name.replace(/\.[^.]+$/, '') || '拾字') + (translation ? '-中文译文' : '-识别原文') + '.txt'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  if (translation && pages.some(p => !p.translation)) toast('已导出已翻译页面；其余页面可逐页翻译后再次导出。');
}
$('copyText').onclick = () => copy('sourceText'); $('copyTranslation').onclick = () => copy('translatedText');
$('exportText').onclick = () => download(false); $('exportTranslation').onclick = () => download(true);
updateControls();
