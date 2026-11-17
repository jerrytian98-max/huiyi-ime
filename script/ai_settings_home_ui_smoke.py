"""Verify the AI settings entry on the home list and its removal from the overflow menu."""
import json
import re
import time
import t9_ui_smoke as ui

entry_labels=('AI settings','AI 设置','AI 設定')


def find_any(labels):
    deadline=time.monotonic()+45
    while time.monotonic()<deadline:
        for node in ui.nodes():
            if node.get('text') in labels or node.get('content-desc') in labels:
                return node
        time.sleep(.4)
    raise AssertionError('Missing navigation item: '+str(labels))


def tap_node(node):
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
    ui.tap((x1+x2)//2,(y1+y2)//2)
    time.sleep(1)


version=re.search(r'versionName=([^\s]+)',ui.shell('dumpsys package '+ui.pkg)).group(1)
result={'version':version,'api':int(ui.shell('getprop ro.build.version.sdk')),'external_ai_calls':0,'checks':[]}
try:
    ui.shell('am start -W -n '+ui.pkg+'/com.osfans.trime.ui.main.MainActivity')
    ui.shell('ime enable '+ui.ime)
    ui.shell('ime set '+ui.ime)
    ui.shell('am start -W -n '+ui.pkg+'/com.osfans.trime.ui.main.MainActivity')
    entry=find_any(entry_labels)
    section=find_any(('AI features','AI 功能'))
    assert int(re.findall(r'\d+',section.get('bounds'))[1]) < int(re.findall(r'\d+',entry.get('bounds'))[1])
    ui.picture('ai-settings-home.png')
    result['checks'].append('AI settings appears in the AI features section of the home list')
    tap_node(entry)
    ui.find('AI 回复')
    ui.find('保存设置')
    assert 'AiSettingsActivity' in ui.shell('dumpsys activity activities')
    result['checks'].append('Home-list entry opens the existing AI settings page')
    print('PASS: home-list entry opens AI settings',flush=True)
    ui.shell('input keyevent BACK')
    find_any(entry_labels)
    result['checks'].append('Back returns to the home settings list')
    tap_node(find_any(('More options','更多选项')))
    find_any(('About','关于','關於'))
    labels=[n.get('text','') for n in ui.nodes()]
    assert not any(label in labels for label in ('Huiyi AI Settings','会意 AI 设置','會意 AI 設定'))
    ui.picture('ai-settings-overflow.png')
    result['checks'].append('Overflow menu no longer contains the AI settings item')
    print('PASS: return navigation and overflow-menu removal',flush=True)
    ui.shell('input keyevent BACK')
    (ui.evidence/'ai-settings-home-ui-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
finally:
    ui.d.close()
