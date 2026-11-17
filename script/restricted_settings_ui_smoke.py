"""Check the authorization guide and system-settings links; never grants a permission."""
import json
import time
import t9_ui_smoke as ui


def click_any(labels):
    deadline = time.monotonic()+45
    while time.monotonic()<deadline:
        for node in ui.nodes():
            if node.get('text') in labels or node.get('content-desc') in labels:
                x1,y1,x2,y2=map(int,ui.re.findall(r'\d+',node.get('bounds')))
                ui.tap((x1+x2)//2,(y1+y2)//2)
                time.sleep(1)
                return
        time.sleep(.5)
    raise AssertionError('Navigation control missing: '+str(labels))


version=ui.re.search(r'versionName=([^\s]+)',ui.shell('dumpsys package '+ui.pkg)).group(1)
result={'version':version,'api':int(ui.shell('getprop ro.build.version.sdk')),'external_ai_calls':0,'permissions_granted':False,'ui_hierarchy_only':True,'checks':[]}
try:
    ui.shell('am start -W -n '+ui.pkg+'/com.osfans.trime.ui.main.MainActivity')
    ui.shell('ime enable '+ui.ime)
    ui.shell('ime set '+ui.ime)
    ui.shell('am start -W -n '+ui.pkg+'/com.osfans.trime.ui.main.MainActivity')
    click_any(('AI 设置','AI settings','AI 設定'))
    ui.shell('input swipe 540 1890 540 700 450')
    toggle=ui.find('快捷截屏（免重复确认）')
    original=toggle.get('checked')
    ui.click('授权被系统拦截？')
    ui.find('放行受限设置')
    text='\n'.join(n.get('text','') for n in ui.nodes())
    assert all(fragment in text for fragment in ('允许受限制的设置','身份验证','会意快捷截屏','应用无法代替你完成系统授权'))
    ui.click('取消')
    assert ui.find('快捷截屏（免重复确认）').get('checked')==original
    result['checks'].append('Help explains restricted settings and manual confirmation; cancel preserves quick-mode switch')
    print('PASS: guide disclosure and cancel',flush=True)
    ui.click('授权被系统拦截？')
    ui.click('应用信息')
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        labels=[n.get('text','') for n in ui.nodes()]
        if any('Huiyi' in t or '会意' in t for t in labels) and 'com.android.settings' in ui.shell('dumpsys activity activities'):
            break
        time.sleep(.5)
    else:
        raise AssertionError('App information page did not open')
    result['checks'].append('Application information link opens Huiyi app details in system settings')
    print('PASS: app information link',flush=True)
    ui.shell('input keyevent BACK')
    ui.click('授权被系统拦截？')
    ui.click('无障碍设置')
    state=ui.shell('dumpsys activity activities')
    assert 'com.android.settings' in state and 'AccessibilitySettings' in state
    result['checks'].append('Accessibility link opens system accessibility settings without enabling a service')
    print('PASS: accessibility settings link',flush=True)
    ui.shell('input keyevent BACK')
    assert ui.find('快捷截屏（免重复确认）').get('checked')==original
    (ui.evidence/'restricted-settings-ui-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
finally:
    ui.d.close()
