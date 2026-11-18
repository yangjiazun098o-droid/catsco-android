"""Build the complete CatsCo library and Android vectors from reviewed SVG sources.

The supplied draft stays intact. Additions use its 24-unit grid, 1.5-unit round
stroke and currentColor. No downloaded build script is executed.
"""
from pathlib import Path
import json
import re
import html
import xml.etree.ElementTree as ET
import subprocess
import os
from icon_optical_source import REDRAW

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'design-assets/icons-20261117'
OUT = ROOT / 'design-assets/icons-complete'
DRAWABLE = ROOT / 'app/src/main/res/drawable'

def path(d): return f'<path d="{d}"/>'
def rect(x, y, w, h, r): return f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}"/>'
def circle(x, y, r): return f'<circle cx="{x}" cy="{y}" r="{r}"/>'
def svg(body, width=1.5):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round">{body}</svg>'

additions = {
    'attachment-camera': ('附件栏 · 拍照', '<path fill="currentColor" stroke="none" fill-rule="evenodd" d="M3 6h4l2-3h6l2 3h4v14H3ZM12 8a4.5 4.5 0 1 0 0 9 4.5 4.5 0 0 0 0-9Z"/>'),
    'attachment-photo': ('附件栏 · 照片', '<path fill="currentColor" stroke="none" fill-rule="evenodd" d="M3 4h18v16H3ZM5 6v10l4-4 4 4 3-3 3 3V6Z"/><circle cx="8" cy="9" r="1.4" fill="currentColor" stroke="none"/>'),
    'attachment-folder': ('附件栏 · 文件', '<path fill="currentColor" stroke="none" fill-rule="evenodd" d="M3 4h6l2 2h10v14H3ZM5 9v2h14V9Z"/>'),
    'agent': ('Agent 默认头像', path('M4.5 19V5l7.5 5.5L19.5 5v14M8 19v-5l4 2.5 4-2.5V19')),
    'attachment': ('附件', path('m8 12 6-6a3.2 3.2 0 0 1 4.5 4.5l-8 8a4.5 4.5 0 0 1-6.4-6.4L12 4M8 12l-1 1a1.8 1.8 0 0 0 2.5 2.5l7-7')),
    'camera': ('拍照', path('M7.5 6.5 9 4.5h6l1.5 2H19a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2Z')+circle(12,13,3.5)),
    'chart': ('模型用量', path('M4.5 4v14a2 2 0 0 0 2 2H20M9 15v-4M13.5 15V6M18 15V9')),
    'cloud': ('云端文件', path('M7 19a4.5 4.5 0 1 1-.5-9 6 6 0 0 1 11.6-1A5 5 0 0 1 18 19H7Z')),
    'contrast': ('外观', circle(12,12,8.5)+path('M12 3.5v17')+'<path d="M12 3.5a8.5 8.5 0 0 1 0 17Z" fill="currentColor" stroke="none"/>'),
    'delete': ('删除', path('M4 7h16M9 7V4.5h6V7M6.5 7l.8 11a2 2 0 0 0 2 2h5.4a2 2 0 0 0 2-2l.8-11M10 10.5v6M14 10.5v6')),
    'document': ('文档与文件', path('M13 3.5H7a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-9L13 3.5ZM13 3.5v6h6M8.5 13h7M8.5 16.5H13')),
    'eye-off': ('隐藏预览', path('m3.5 3.5 17 17M9 5.5c1-.3 2-.5 3-.5 4.5 0 8 4 9 7a13 13 0 0 1-3.1 4.2M6 6.7A15 15 0 0 0 3 12c1 3 4.5 7 9 7 1.6 0 3.1-.5 4.5-1.3M9.5 9.5a3.5 3.5 0 0 0 5 5')),
    'image': ('照片与图片', rect(3.5,4.5,17,15,2.5)+circle(8.5,9,1.5)+path('m4 17 5-4 3.5 3 3.5-5 4 5')),
    'microphone': ('语音输入', rect(9,3,6,12,3)+path('M5.5 11.5v.5a6.5 6.5 0 0 0 13 0v-.5M12 18.5V21M9 21h6')),
    'pin': ('置顶', path('M8 3.5h8M9 3.5v6L6 14v1h12v-1l-3-4.5v-6M12 15v6')),
    'refresh': ('刷新与重新生成', path('M20 8a8.5 8.5 0 0 0-14-2L3.5 8.5M3.5 4v4.5H8M4 16a8.5 8.5 0 0 0 14 2l2.5-2.5M16 15.5h4.5V20')),
    'quote': ('引用消息', path('M10 5.5C6 6 3.5 9 3.5 13v5.5H10V12H6M20.5 5.5C16.5 6 14 9 14 13v5.5h6.5V12h-4')),
    'sparkle': ('技能与权益', path('m10 3 2.2 6.8L19 12l-6.8 2.2L10 21l-2.2-6.8L1 12l6.8-2.2L10 3ZM19.5 2v5M17 4.5h5')),
    'star': ('收藏', path('m12 3 2.8 5.7 6.3.9-4.6 4.4 1.1 6.3-5.6-3-5.6 3 1.1-6.3L2.9 9.6l6.3-.9L12 3Z')),
    'widgets': ('实时活动与组件', rect(3.5,4,17,6,1.8)+rect(3.5,13.5,7,6.5,1.8)+rect(14,13.5,6.5,6.5,1.8)),
    'arrow-right': ('下一步', path('M4 12h16m-6-6 6 6-6 6')),
    'stop': ('停止生成', '<rect x="5" y="5" width="14" height="14" rx="3" fill="currentColor" stroke="none"/>'),
    'circle': ('计划待处理', circle(12,12,9)),
    'check-circle': ('计划已完成', circle(12,12,9)+path('m7.5 12 3 3 6-6')),
    'progress-circle': ('计划进行中', circle(12,12,9)+'<circle cx="12" cy="12" r="2" fill="currentColor" stroke="none"/>'),
}

mapping = {
    'e_attachment_camera':'attachment-camera', 'e_attachment_photo':'attachment-photo', 'e_attachment_folder':'attachment-folder',
    'e_account':'user', 'e_add':'plus', 'e_agent':'agent', 'e_apps':'grid', 'e_attach':'attachment',
    'e_camera':'camera', 'e_chart':'chart', 'e_chat':'message', 'e_close':'close', 'e_cloud':'cloud',
    'e_computer':'monitor', 'e_contrast':'contrast', 'e_delete':'delete', 'e_download':'download',
    'e_edit':'edit', 'e_expand':'chevron-down', 'e_external':'arrow-up-right', 'e_file':'document',
    'e_image':'image', 'e_key':'key', 'e_language':'globe', 'e_menu':'list', 'e_mic':'microphone',
    'e_more':'more', 'e_next':'arrow-right', 'e_notifications':'bell', 'e_people':'team', 'e_pin':'pin',
    'e_refresh':'refresh', 'e_search':'search', 'e_select':'check', 'e_send':'send',
    'e_send_active':'send-active', 'e_settings':'settings', 'e_sidebar':'sidebar', 'e_sparkle':'sparkle',
    'e_star':'star', 'e_widgets':'widgets', 'message_copy':'copy', 'message_document':'document',
    'message_eye':'eye', 'message_eye_off':'eye-off', 'message_quote':'quote', 'message_terminal':'terminal',
    'e_sidebar_active':'sidebar-active', 'e_new_task':'new-task', 'e_knowledge':'knowledge', 'e_stop':'stop', 'e_circle':'circle',
    'e_check_circle':'check-circle', 'e_progress_circle':'progress-circle', 'e_chevron_right':'chevron-right',
}

def android_vector(markup):
    root = ET.fromstring(markup)
    def color(value):
        if value == 'none': return '#00000000'
        if value == 'currentColor': return '#FF1F1F1F'
        if value.startswith('#'): return value
        raise ValueError(f'Unsupported color: {value}')
    def convert(node, inherited):
        tag = node.tag.split('}')[-1]
        attrs = inherited | node.attrib
        if tag in ('svg','g'):
            body = '\n'.join(convert(child, attrs) for child in node)
            transform = node.get('transform')
            if transform:
                match = re.fullmatch(r'scale\(([\d.]+)\)', transform)
                matrix = re.fullmatch(r'matrix\(([-\d.]+) 0 0 ([-\d.]+) ([-\d.]+) ([-\d.]+)\)', transform)
                if match:
                    body = f'<group android:scaleX="{match[1]}" android:scaleY="{match[1]}">{body}</group>'
                elif matrix:
                    body = f'<group android:scaleX="{matrix[1]}" android:scaleY="{matrix[2]}" android:translateX="{matrix[3]}" android:translateY="{matrix[4]}">{body}</group>'
                else: raise ValueError(transform)
            return body
        if tag == 'path': d = node.attrib['d']
        elif tag == 'circle':
            x,y,r = (float(attrs[k]) for k in ('cx','cy','r'))
            d = f'M{x-r},{y}a{r},{r} 0 1,0 {2*r},0a{r},{r} 0 1,0 {-2*r},0Z'
        elif tag == 'rect':
            x,y,w,h = (float(attrs.get(k,0)) for k in ('x','y','width','height'))
            r = float(attrs.get('rx',0)); ry = float(attrs.get('ry',r))
            d = f'M{x+r},{y}H{x+w-r}A{r},{ry} 0 0,1 {x+w},{y+ry}V{y+h-ry}A{r},{ry} 0 0,1 {x+w-r},{y+h}H{x+r}A{r},{ry} 0 0,1 {x},{y+h-ry}V{y+ry}A{r},{ry} 0 0,1 {x+r},{y}Z'
        else: raise ValueError(f'Unsupported SVG element {tag}')
        values = dict(pathData=d,fillColor=color(attrs.get('fill','currentColor')),
                      strokeColor=color(attrs.get('stroke','none')),strokeWidth=attrs.get('stroke-width','0'),
                      strokeLineCap=attrs.get('stroke-linecap','butt'),strokeLineJoin=attrs.get('stroke-linejoin','miter'),
                      fillType='evenOdd' if attrs.get('fill-rule') == 'evenodd' else 'nonZero')
        return '<path '+ ' '.join(f'android:{k}="{html.escape(str(v),quote=True)}"' for k,v in values.items())+'/>'
    return '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'+convert(root,{})+'\n</vector>\n'

def main():
    (OUT/'svg').mkdir(parents=True,exist_ok=True)
    (OUT/'android').mkdir(exist_ok=True)
    original = json.loads((SOURCE/'manifest.json').read_text(encoding='utf-8'))
    records = original['icons']
    icons = {r['name']:(SOURCE/'svg'/f'{r["name"]}.svg').read_text(encoding='utf-8') for r in records}
    for name,(label,body) in additions.items():
        icons[name]=svg(body)
        records.append(dict(name=name,label=label,status='supplemented',change='按提供草案的 24px 网格、1.5px 圆端描边补齐。'))
    assert set(REDRAW)=={r['name'] for r in records if r['status']=='normalized'}
    for name,body in REDRAW.items(): icons[name]=svg(body)
    icons['sidebar']=svg(path('M4 6h16M4 12h16M4 18h16'))
    icons['sidebar-active']=icons['list']
    icons['send']=svg(path('M12 20V5M6 11l5-5q1-1 2 0l5 5'))
    for record in records:
        if record['name'] == 'sidebar': record.update(label='打开侧栏', change='用户指定的三条横线。')
        if record['name'] == 'sidebar-active': record.update(label='关闭侧栏', change='用户指定的三个点加三条横线。')
        if record['name'] == 'send': record.update(label='发送', change='恢复此前的向上箭头轮廓，沿用统一线宽。')
    icons['contrast']=svg(circle(12,12,8.5)+path('M12 3.5v17')+'<path d="M12 3.5a8.5 8.5 0 0 1 0 17Z" fill="currentColor" stroke="none"/>')
    icons['stop']=svg(rect(5,5,14,14,2.5))
    icons['send-active']=icons['send']
    records.append(dict(name='send-active',label='发送可用态',status='derived',change='按最新统一线宽要求，与发送保持相同的 1.5px 描边。'))
    for name,markup in icons.items(): (OUT/'svg'/f'{name}.svg').write_text(markup,encoding='utf-8')
    node=os.environ.get('CATSCO_NODE','E:/CodexProjects/.tools/node-v24.5.0-win-x64/node.exe')
    subprocess.run([node,str(ROOT/'scripts/calibrate-icons.cjs'),str(OUT/'svg')],check=True)
    icons={name:(OUT/'svg'/f'{name}.svg').read_text(encoding='utf-8') for name in icons}
    symbols=''.join(markup.replace('<svg ',f'<symbol id="catsco-{name}" ',1).replace('</svg>','</symbol>') for name,markup in icons.items())
    (OUT/'sprite.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg"><defs>'+symbols+'</defs></svg>',encoding='utf-8')
    (OUT/'icons.mjs').write_text('// Source and per-icon provenance: manifest.json\nexport const icons = '+json.dumps(icons,ensure_ascii=False,indent=2)+';\n',encoding='utf-8')
    sources = list((ROOT/'app/src/main/java').rglob('*.kt'))
    usages = {}
    for f in sources:
        for number,line in enumerate(f.read_text(encoding='utf-8').splitlines(),1):
            for resource in re.findall(r'R\.drawable\.([a-z_0-9]+)',line):
                usages.setdefault(resource,[]).append(f'{f.relative_to(ROOT).as_posix()}:{number}')
    for resource,name in mapping.items():
        vector=android_vector(icons[name])
        (DRAWABLE/f'{resource}.xml').write_text(vector,encoding='utf-8')
        (OUT/'android'/f'{resource}.xml').write_text(vector,encoding='utf-8')
    uncovered = set(usages)-set(mapping)-{'catsco_wordmark','catsco_mark'}
    if uncovered: raise ValueError(f'Unmapped application icons: {uncovered}')
    for r in records:
        r['opticalCalibration']='模型用量 chart：23dp 按钮图标、等效 1.5px 描边；17.5px 视觉长边，圆形适度超出。'
        if r['name'].startswith('attachment-'):
            r['opticalCalibration']='按用户附件栏参考单独适配：30dp 实心图标、20px 视觉长边、64dp 圆角底板。'
            r['change']='根据用户提供的附件菜单参考，补充实心相机、照片与文件夹造型。'
        if r['name'] in REDRAW: r['change']='沿用原图语义与轮廓特征，重绘为与模型用量一致的圆端描边。'
        r['androidResources']=[k for k,v in mapping.items() if v==r['name']]
        r['usages']=[u for resource in r['androidResources'] for u in usages.get(resource,[])]
    result=dict(name='CatsCo complete Android icon library',source=original['source'],author=original['author'],
                licenseStatus=original['licenseStatus'],total=len(records),provided=61,supplemented=len(additions),
                derived=1,androidResources=len(mapping),icons=records)
    (OUT/'manifest.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    table=['# CatsCo Android 图标清单','',f'共 {len(records)} 个 SVG；61 个来自提供的草案，{len(additions)} 个补齐图标，1 个发送状态。映射到 {len(mapping)} 个 Android 资源。','',
           '常规操作图标按模型用量校准为 23dp、等效描边 1.5px。附件栏按用户新参考单独使用 30dp 实心图标与 64dp 圆角底板。37 个原填充轮廓重绘为描边。', '',
           '| 图标 | 用途 | 来源状态 | Android 资源 | 当前引用次数 |','| --- | --- | --- | --- | --- |']
    for r in records: table.append(f'| {r["name"]} | {r["label"]} | {r["status"]} | {", ".join(r["androidResources"]) or "库内备用"} | {len(r["usages"])} |')
    table += ['', '## 范围说明','', 'CatsCo 字体 Logo、启动图标与品牌图片保持原资源；用户和 Agent 的远程头像继续使用真实图片。',
              '原生复选框、单选框、开关、输入框内部控件和系统选择工具栏沿用 Android/Compose 自带的状态图形；五点加载反馈继续使用现有 Canvas 动画。',
              '手绘的停止、计划状态与消息展开箭头已纳入本图标库。未在当前版本使用的原库图标也完整保留，未凭空添加新功能入口。',
              '每个资源的具体代码引用见 manifest.json 的 usages。', '', '## 来源','',
              f'用户提供草案来源：{original["source"]}，作者 {original["author"]}。保留原始出处及许可状态；新增图标标记为 supplemented，未将原图宣称为原创。',
              '输入草案保存在 ../icons-20261117。运行 python scripts/sync-icon-library.py 重新生成。']
    (OUT/'README.md').write_text('\n'.join(table)+'\n',encoding='utf-8')
    cards=[]
    for r in records:
        status='补齐并校准' if r['status']=='supplemented' else '状态变体' if r['status']=='derived' else '原库视觉校准'
        cards.append(f'<article data-q="{r["name"]} {r["label"]}" data-used="{bool(r["usages"])}" data-added="{r["status"] in ("supplemented","derived")}"><div class="symbol">{icons[r["name"]]}</div><h2>{r["label"]}</h2><code>{r["name"]}</code><p>{status} · {"App 已使用" if r["usages"] else "库内备用"}</p><a href="svg/{r["name"]}.svg" download>SVG</a></article>')
    (OUT/'preview.html').write_text('''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>CatsCo 图标清单</title>
<style>*{box-sizing:border-box}body{margin:0;background:#f8f8f8;color:#1f1f1f;font:14px system-ui;--card:#fff;--border:#e4e4e8;--muted:#747479;--icon-knockout:#fff;--size:23px}body.dark{background:#0b0b0b;color:#ececec;--card:#151515;--border:#303030;--muted:#aaa;--icon-knockout:#151515}main{max-width:1200px;margin:auto;padding:40px 24px}h1{font-size:30px;font-weight:500}header p{color:var(--muted);line-height:1.8}.tools{display:flex;flex-wrap:wrap;gap:10px;margin:26px 0;position:sticky;top:0;background:inherit;padding:12px 0}input,select,button{font:inherit;padding:10px;border:1px solid var(--border);border-radius:8px;color:inherit;background:var(--card)}input{flex:1;min-width:180px}button{cursor:pointer}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:12px}article{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:20px}.symbol{height:58px;display:flex;align-items:center}.symbol svg{width:var(--size);height:var(--size)}h2{font-size:14px;font-weight:500;margin:12px 0 6px}code,p,a{font-size:12px}article p,code{color:var(--muted)}a{color:inherit}[hidden]{display:none!important}:focus-visible{outline:2px solid #29bc95;outline-offset:3px}</style>
<main><header><h1>CatsCo · 完整图标清单</h1><p>以模型用量图标为标尺：统一 23dp、等效 1.5px 描边，逐项校准可见尺寸和留白。<br>61 个原库图标、25 个补齐图标及 1 个发送状态，共 87 个。附件栏另用 30dp 实心图标与圆角底板。</p></header><div class="tools"><input id="search" placeholder="搜索名称或用途" aria-label="搜索图标"><select id="filter" aria-label="筛选"><option value="all">全部图标</option><option value="used">App 已使用</option><option value="added">本轮补齐</option></select><select id="size" aria-label="图标尺寸"><option>16</option><option>20</option><option selected>23</option><option>32</option></select><button id="theme">切换深浅色</button></div><div class="grid">'''+''.join(cards)+'''</div><p>原库：Aichat / UI_ivan；输入草案及来源记录已保留。</p></main><script>
const search=document.getElementById('search'),filter=document.getElementById('filter');function update(){document.querySelectorAll('article').forEach(x=>x.hidden=!x.dataset.q.toLowerCase().includes(search.value.toLowerCase())||(filter.value==='used'&&x.dataset.used!=='True')||(filter.value==='added'&&x.dataset.added!=='True'))}search.oninput=filter.onchange=update;document.getElementById('size').onchange=e=>document.body.style.setProperty('--size',e.target.value+'px');document.getElementById('theme').onclick=()=>document.body.classList.toggle('dark');</script></html>''',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='icons'},ensure_ascii=False))

if __name__=='__main__': main()
