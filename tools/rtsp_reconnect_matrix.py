#!/usr/bin/env python3
"""Real-device RTSP/AAC matrix. No synthetic result is ever promoted to device PASS."""
import argparse
import csv
import itertools
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid

PACKAGE = "com.example.motro"
ACTIVITY = PACKAGE + "/.RtspMatrixActivity"
FAULTS = ("network", "physical_link", "service_restart", "source_404")


def cases():
    return [dict(caseId=f"R{i:02d}", transport=t, audio=a, recording=r, faultType=f)
            for i, (f, t, a, r) in enumerate(itertools.product(FAULTS, ("tcp", "udp"),
                                                               (False, True), (False, True)), 1)]


def redact(text):
    return re.sub(r"rtsps?://[^\s\"<>]+", "rtsp://[REDACTED]", str(text), flags=re.I)


def initial_result(case, reason):
    return dict(case, disconnectDetectedMs=None, reconnectAttempts=None, reconnectElapsedMs=None,
                finalState=None, videoRecovered=None, audioRecovered=None, recordingRecovered=None,
                status="NOT_RUN", failureReason=reason, rounds=[])


def baseline_errors(case, stats):
    errors = []
    if stats.get('playerState') != 'PLAYING' or stats.get('renderedFrameCount', 0) <= 10:
        errors.append('video baseline not playing')
    if stats.get('effectiveRtspTransport') != case['transport']:
        errors.append('effective RTSP transport differs from requested case')
    if stats.get('audioPacketCount', 0) <= 0:
        errors.append('no source AAC packets observed; SDP codec declaration alone is insufficient')
    if stats.get('reconnectAttemptCount', 0) != 0:
        errors.append('source disconnected during baseline stability window')
    if case['audio']:
        if not stats.get('audioPlaybackClockValid') or stats.get('audioSinkWriteCount', 0) <= 0:
            errors.append('audio baseline has no PCM playback/valid clock')
    elif stats.get('audioWorkerRunning') or stats.get('audioSinkWriteCount', 0):
        errors.append('audio OFF unexpectedly started output')
    recorder = stats.get('recorder', {})
    if case['recording'] and (recorder.get('writeErrors', 0) or recorder.get('audioPacketCount', 0) <= 0):
        errors.append('recording baseline error or missing AAC packets: '+recorder.get('lastError', ''))
    return errors


def evaluate_round(case, events, before, limits=None):
    """Evaluate only observed device samples, using device elapsedRealtime throughout."""
    limits = limits or {}
    reasons = []
    fault = next((e for e in events if e['kind'] == 'fault'), None)
    restored = next((e for e in events if e['kind'] == 'restored'), None)
    ev = [e for e in events if e['kind'] == 'event']
    disconnected = next((e for e in ev if e['data'].get('event') == 'reconnect_disconnected'), None)
    success = next((e for e in ev if e['data'].get('event') == 'reconnect_success'
                    and restored and e['t'] >= restored['t']), None)
    retries = [e for e in ev if e['data'].get('event') == 'reconnecting']
    stats = [e for e in events if e['kind'] == 'stats']
    after = [e for e in stats if success and e['t'] >= success['t'] and e['data'].get('playerState') == 'PLAYING']
    if not fault or not disconnected or disconnected['t'] < fault['t']: reasons.append('disconnect not detected after fault')
    if not restored or not success: reasons.append('no reconnect success after restoration')
    if not retries: reasons.append('no reconnect/backoff event')
    for e in retries:
        data = e['data']; attempt = data.get('attempt', 0)
        expected = min(1000 * 2 ** min(max(attempt - 1, 0), 8), 5000)
        if attempt < 1 or data.get('delayMs') != expected: reasons.append('unexpected retry backoff'); break
    for first, second in zip(retries, retries[1:]):
        if second['t'] - first['t'] < first['data'].get('delayMs', 0) - 100:
            reasons.append('retry loop is faster than backoff'); break
    if case['faultType'] == 'source_404' and not any(e['data'].get('event') == 'waiting_source'
                                                   and e['data'].get('source404') for e in ev):
        reasons.append('no actual 404 WAITING_SOURCE evidence')
    if any(e['kind'] == 'error' for e in events): reasons.append('harness/player operation error')
    if any(e['data'].get('playerState') == 'ERROR' for e in stats): reasons.append('player entered ERROR')
    end = after[-1]['data'] if after else (stats[-1]['data'] if stats else {})
    if any(e['data'].get('effectiveRtspTransport') != case['transport'] for e in after):
        reasons.append('effective transport changed')
    video = len(after) >= 2 and end.get('videoFrameCount', 0) > before.get('videoFrameCount', 0) \
            and end.get('renderedFrameCount', 0) > after[0]['data'].get('renderedFrameCount', 0)
    if not video: reasons.append('video decode/render did not resume')
    if end.get('videoCodec', '').lower() not in ('h264', 'hevc', 'h265') or end.get('audioCodec', '').lower() != 'aac':
        reasons.append('source is not H264/H265 + AAC')
    if case['audio']:
        audio = len(after) >= 2 and end.get('audioPlaybackClockValid') is True \
                and end.get('audioSinkWriteCount', 0) > before.get('audioSinkWriteCount', 0) \
                and end.get('audioPlaybackClockUs', 0) > after[0]['data'].get('audioPlaybackClockUs', 0) \
                and end.get('audioQueueGeneration', 0) > before.get('audioQueueGeneration', 0) \
                and end.get('audioClockGeneration') == end.get('audioQueueGeneration') \
                and end.get('audioWorkerRunning') is True and end.get('audioPlayable') is True
    else:
        audio = bool(after) and all(not e['data'].get('audioWorkerRunning', True)
                    and e['data'].get('audioSinkWriteCount', -1) == before.get('audioSinkWriteCount', 0)
                    and not e['data'].get('audioEnabled', True) for e in stats)
    if not audio: reasons.append('audio lifecycle/clock recovery mismatch')
    recorder = end.get('recorder', {})
    recording = not case['recording'] or (recorder.get('recording') is True
                and recorder.get('packetsWritten', 0) > before.get('recorder', {}).get('packetsWritten', 0)
                and recorder.get('audioPacketCount', 0) > before.get('recorder', {}).get('audioPacketCount', 0)
                and recorder.get('completedSegmentCount', 0) > before.get('recorder', {}).get('completedSegmentCount', 0)
                and recorder.get('writeErrors', -1) == 0 and recorder.get('queueDrops', -1) == 0)
    if not recording: reasons.append('recorder did not recover cleanly')
    for key, allowance in [('fdCount', 4), ('threadCount', 4), ('nativeHeapBytes', 16 * 1024 * 1024)]:
        if key not in before or key not in end or before.get(key, -1) < 0 or end.get(key, -1) < 0:
            reasons.append('resource metric unavailable: ' + key)
        elif end[key] - before[key] > limits.get(key, allowance): reasons.append('resource growth: ' + key)
    beats = [e['t'] for e in events if e['kind'] == 'heartbeat']
    if len(beats) < 2 or max((b-a for a, b in zip(beats, beats[1:])), default=99999) > 5000:
        reasons.append('main-thread heartbeat missing/ANR suspected')
    return dict(disconnectDetectedMs=disconnected['t']-fault['t'] if disconnected and fault else None,
                reconnectAttempts=len(retries), reconnectElapsedMs=success['t']-restored['t'] if success and restored else None,
                finalState=end.get('playerState'), videoRecovered=video, audioRecovered=audio,
                recordingRecovered=recording, status='FAIL' if reasons else 'PASS', failureReason='; '.join(dict.fromkeys(reasons)))


def write_report(directory, results):
    directory.mkdir(parents=True, exist_ok=True)
    normalized = [dict(result, passFail=result['status']) for result in results]
    (directory/'results.json').write_text(json.dumps(normalized, ensure_ascii=False, indent=2), encoding='utf-8')
    fields = ['caseId', 'transport', 'audio', 'recording', 'faultType', 'disconnectDetectedMs', 'reconnectAttempts',
              'reconnectElapsedMs', 'finalState', 'videoRecovered', 'audioRecovered', 'recordingRecovered',
              'passFail', 'failureReason']
    with (directory/'results.csv').open('w', encoding='utf-8-sig', newline='') as output:
        writer = csv.DictWriter(output, fields, extrasaction='ignore'); writer.writeheader(); writer.writerows(normalized)
    counts = {s: sum(r['status'] == s for r in normalized) for s in ('PASS', 'FAIL', 'NOT_RUN')}
    lines = ['# RTSP_RECONNECT_MATRIX', '', '真实设备结果：' + ' / '.join(f'{k} {v}' for k, v in counts.items()), '',
             '仅实际完成故障注入、恢复、指标检查及必要人工听音确认后标记 PASS。基线播放不等于重连通过。', '',
             '| Case | Transport | Audio | Recording | Fault | Result | Reason |', '|---|---|---|---|---|---|---|']
    for r in results:
        lines.append('| ' + ' | '.join(str(r[k]).replace('|', '/') for k in ('caseId','transport','audio','recording','faultType','status','failureReason')) + ' |')
    lines += ['', '逐轮时延、事件、stats、资源趋势及录制文件校验见 results.json 与各 Case 目录。',
              '人工拔线请保留 USB adb；恢复后继续观察至少 8 秒。Audio=ON 的旧 PCM/爆音需人工听音确认。']
    (directory/'RTSP_RECONNECT_MATRIX.md').write_text('\n'.join(lines)+'\n', encoding='utf-8')


class Device:
    def __init__(self, serial, adb='adb'):
        self.prefix = [adb, '-s', serial]

    def call(self, *args, data=None, timeout=30):
        result = subprocess.run(self.prefix+list(args), input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode: raise RuntimeError('adb command failed: '+redact(result.stderr.decode(errors='replace')))
        return result.stdout

    def command(self, command):
        self.call('shell', 'am', 'start', '-n', ACTIVITY, '--es', 'command', command)


class Capture:
    def __init__(self, device, session, path):
        self.events = []; self.lock = threading.Lock(); self.session = session
        self.file = path.open('w', encoding='utf-8')
        self.native_file = path.with_name('native.log').open('w', encoding='utf-8')
        self.process = subprocess.Popen(device.prefix+['logcat', '-v', 'raw', '-T', '1', 'RTSPMatrix:I', 'FFmpegNative:V', '*:S'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.thread = threading.Thread(target=self.read, daemon=True); self.thread.start()

    def read(self):
        for raw in self.process.stdout:
            try: event = json.loads(raw.decode('utf-8'))
            except (ValueError, UnicodeError):
                self.native_file.write(redact(raw.decode('utf-8', errors='replace')))
                self.native_file.flush()
                continue
            if event.get('caseId') != self.session: continue
            with self.lock:
                self.events.append(event); self.file.write(json.dumps(event, ensure_ascii=False)+'\n'); self.file.flush()

    def snapshot(self):
        with self.lock: return list(self.events)

    def wait(self, predicate, timeout, allow_errors=False):
        end = time.monotonic()+timeout
        while time.monotonic() < end:
            events = self.snapshot()
            if not allow_errors and any(e['kind'] == 'error' for e in events): raise RuntimeError('device harness reported operation error')
            for event in events[-4:]:
                recorder = event.get('data', {}).get('recorder', {})
                if not allow_errors and recorder.get('state') == 'error':
                    raise RuntimeError('recorder error: '+recorder.get('lastError', 'unknown'))
            value = predicate(events)
            if value: return value
            time.sleep(.2)
        raise TimeoutError('device observation timed out')

    def close(self):
        self.process.terminate(); self.process.wait(timeout=5); self.thread.join(timeout=5)
        self.file.close(); self.native_file.close()


def play(device, config, case, directory):
    session = case['caseId']+'_'+uuid.uuid4().hex[:8]
    device.call('shell', 'am', 'force-stop', PACKAGE)
    capture = Capture(device, session, directory/'events.jsonl')
    payload = dict(case, caseId=session, url=config['url'], segmentSeconds=config.get('segmentSeconds', 5))
    try:
        device.call('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files')
        device.call('shell', 'run-as', PACKAGE, 'sh', '-c', "'cat > files/rtsp-matrix.json'", data=json.dumps(payload).encode())
        device.call('shell', 'am', 'start', '-n', ACTIVITY)
        capture.wait(lambda events: any(e['kind'] == 'started' for e in events), config.get('startupSeconds', 45))
        capture.wait(lambda events: [e for e in events if e['kind'] == 'stats' and e['data'].get('playerState') == 'PLAYING'
                     and e['data'].get('renderedFrameCount', 0) > 10
                     and (not case['recording'] or e['data'].get('recorder', {}).get('packetsWritten', 0) > 10)], config.get('startupSeconds', 45))
        time.sleep(config.get('baselineSeconds', 5))
        return capture
    except Exception:
        # Preserve diagnostic evidence and attempt a normal trailer even when startup failed.
        try:
            device.command('stop')
            capture.wait(lambda events: any(e['kind'] == 'stopped' for e in events), 15, allow_errors=True)
        finally:
            capture.close()
            device.call('shell', 'am', 'force-stop', PACKAGE)
        raise


def fault_action(spec, action, case, serial, manual):
    argv = spec.get(action)
    if argv:
        if not isinstance(argv, list) or not all(isinstance(x, str) for x in argv): raise ValueError('fault command must be an argv array')
        env = dict(os.environ, RTSP_MATRIX_SERIAL=serial, RTSP_MATRIX_CASE=case['caseId'])
        result = subprocess.run(argv, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if result.returncode: raise RuntimeError('fault '+action+' command failed')
    elif manual:
        input(f"{case['caseId']} {case['faultType']} {action}: 完成故障操作后按 Enter（保持 USB adb 在线）: ")
    else: raise RuntimeError('no fault trigger configured')


def validate_files(device, capture, directory, ffprobe):
    started = next(e for e in capture.snapshot() if e['kind'] == 'started')
    media = directory/'media'
    device.call('pull', started['data']['recordDir'], str(media), timeout=60)
    files = sorted(media.rglob('*.mp4'))
    if not files: raise RuntimeError('no recording files retrieved')
    for file in files:
        result = subprocess.run([ffprobe, '-v', 'error', '-count_packets', '-show_entries',
                                 'stream=codec_name,codec_type,nb_read_packets', '-of', 'json', str(file)], capture_output=True, timeout=45)
        (file.with_suffix('.probe.json')).write_bytes(result.stdout)
        if result.returncode or result.stderr.strip(): raise RuntimeError('record file cannot be parsed cleanly')
        streams = json.loads(result.stdout)['streams']
        if not any(s.get('codec_name') == 'aac' and int(s.get('nb_read_packets', 0)) > 0 for s in streams): raise RuntimeError('record file has no AAC packets')
        if not any(s.get('codec_name') in ('h264', 'hevc') and int(s.get('nb_read_packets', 0)) > 0 for s in streams): raise RuntimeError('record file has no H264/H265 packets')
    return len(files)


def execute_case(device, config, case, directory, manual=False, baseline=False):
    result = initial_result(case, '')
    capture = None; fault_active = False; fault_injected = False
    result['artifactDirectory'] = str(directory)
    spec = config.get('faults', {}).get(case['faultType'], {})
    directory.mkdir(parents=True, exist_ok=True)
    try:
        capture = play(device, config, case, directory)
        before = [e['data'] for e in capture.snapshot() if e['kind'] == 'stats'][-1]
        if before.get('audioCodec', '').lower() != 'aac' or before.get('videoCodec', '').lower() not in ('h264', 'hevc', 'h265'):
            raise RuntimeError('real source is not H264/H265 + AAC')
        result['baseline'] = before
        errors = baseline_errors(case, before)
        result['baselineStatus'] = 'FAIL' if errors else 'PASS'
        result['baselineFailureReason'] = '; '.join(errors)
        if baseline:
            device.command('stop')
            capture.wait(lambda events: any(e['kind'] == 'stopped' for e in events), config.get('stopSeconds', 15), allow_errors=True)
            if case['recording']:
                try:
                    result['recordFilesValidated'] = validate_files(device, capture, directory, config.get('ffprobe', 'ffprobe'))
                except Exception as error:
                    result['baselineStatus'] = 'FAIL'
                    result['baselineFailureReason'] += '; '+redact(error)
            result['failureReason'] = 'no fault injected; baseline '+result['baselineStatus']+': '+result['baselineFailureReason']
            return result
        if errors:
            result['failureReason'] = 'fault not injected; baseline failed: '+result['baselineFailureReason']
            return result
        for round_no in range(config.get('rounds', 3)):
            start = len(capture.snapshot())
            device.command('fault'); fault_active = True
            fault_action(spec, 'apply', case, config['serial'], manual)
            fault_injected = True
            capture.wait(lambda events: any(e['kind'] == 'event' and e['data'].get('event') == 'reconnect_disconnected' for e in events[start:]), config.get('detectSeconds', 15))
            time.sleep(config.get('faultSeconds', 12))
            device.command('restored')
            fault_action(spec, 'restore', case, config['serial'], manual)
            fault_active = False
            capture.wait(lambda events: any(e['kind'] == 'event' and e['data'].get('event') == 'reconnect_success' for e in events[start:]), config.get('recoverSeconds', 45))
            time.sleep(config.get('observeSeconds', 8))
            measured = evaluate_round(case, capture.snapshot()[start:], before, config.get('resourceGrowthLimits'))
            measured['round'] = round_no+1; result['rounds'].append(measured)
            if measured['status'] != 'PASS': break
            before = [e['data'] for e in capture.snapshot() if e['kind'] == 'stats'][-1]
        if result['rounds']:
            result.update({k: v for k, v in result['rounds'][-1].items() if k != 'round'})
        device.command('stop')
        capture.wait(lambda events: any(e['kind'] == 'stopped' for e in events), config.get('stopSeconds', 15), allow_errors=True)
        stopped_record = next(e['data'] for e in capture.snapshot() if e['kind'] == 'record_stopped')
        if case['recording']:
            if not stopped_record.get('success') or stopped_record.get('queuePackets') != 0: raise RuntimeError('record stop failed or queue not drained')
            result['recordFilesValidated'] = validate_files(device, capture, directory, config.get('ffprobe', 'ffprobe'))
        last = [e['data'] for e in capture.snapshot() if e['kind'] == 'stats'][-1]
        for key, allowance in [('fdCount', 4), ('threadCount', 4), ('nativeHeapBytes', 16*1024*1024)]:
            if last.get(key, 0)-result['baseline'].get(key, 0) > config.get('resourceGrowthLimits', {}).get(key, allowance):
                raise RuntimeError('multi-round resource growth: '+key)
        if case['audio'] and result['status'] == 'PASS':
            if manual:
                clean = input('人工听音确认：恢复后无旧声音回放、爆音、永久静音？输入 yes 确认: ').strip().lower() == 'yes'
                result['audioListeningConfirmed'] = clean
                if not clean: result.update(status='FAIL', failureReason='manual audio listening failed')
            else:
                result.update(status='NOT_RUN', automaticStatus='PASS', failureReason='automatic checks passed; manual audio listening not yet confirmed')
    except Exception as error:
        result.update(status='FAIL' if fault_injected else 'NOT_RUN', failureReason=redact(error))
        if baseline: result.update(baselineStatus='FAIL', baselineFailureReason=redact(error))
    finally:
        if fault_active:
            try: fault_action(spec, 'restore', case, config['serial'], manual)
            except Exception as error: result['failureReason'] += '; RESTORE REQUIRED: '+redact(error)
        if capture:
            try:
                device.command('stop'); capture.wait(lambda events: any(e['kind'] == 'stopped' for e in events), config.get('stopSeconds', 15), allow_errors=True)
            except Exception as error:
                result.update(status='FAIL', failureReason=result['failureReason']+'; stop/release: '+redact(error))
            capture.close()
        device.call('shell', 'am', 'force-stop', PACKAGE)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--case', action='append', default=[])
    parser.add_argument('--run', action='store_true')
    parser.add_argument('--baseline', action='store_true')
    parser.add_argument('--manual', action='store_true')
    parser.add_argument('--resume', action='store_true', help='keep previously saved cases not selected by this run')
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding='utf-8-sig')) if args.config else {}
    results = [initial_result(c, 'not selected/executed; real fault trigger required') for c in cases()]
    if args.resume and (args.output/'results.json').exists():
        previous = {r['caseId']: r for r in json.loads((args.output/'results.json').read_text(encoding='utf-8'))}
        results = [previous.get(r['caseId'], r) for r in results]
    if args.case and set(args.case) - {c['caseId'] for c in cases()}: parser.error('unknown case ID')
    if args.run and args.baseline: parser.error('choose --run or --baseline')
    if args.run or args.baseline:
        if not config.get('url') or not config.get('serial'): parser.error('local config must provide url and serial')
        device = Device(config['serial'], config.get('adb', 'adb'))
        if device.call('get-state').strip() != b'device': parser.error('adb device unavailable')
        for index, case in enumerate(cases()):
            if args.case and case['caseId'] not in args.case: continue
            spec = config.get('faults', {}).get(case['faultType'], {})
            if not args.baseline and not args.manual and not (spec.get('apply') and spec.get('restore')):
                results[index]['failureReason'] = 'device available; no automated fault apply/restore configured; use --manual'
                continue
            print('RUN', case['caseId'], case['transport'], case['audio'], case['recording'], flush=True)
            results[index] = execute_case(device, config, case, args.output/case['caseId']/uuid.uuid4().hex[:8], args.manual, args.baseline)
            print(results[index]['status'], results[index]['failureReason'], flush=True)
            write_report(args.output, results)
    write_report(args.output, results)
    print({s: sum(r['status'] == s for r in results) for s in ('PASS','FAIL','NOT_RUN')})


if __name__ == '__main__':
    main()
