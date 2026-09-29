import json
from pathlib import Path
import tempfile
import unittest

import rtsp_reconnect_matrix as matrix


class MatrixTests(unittest.TestCase):
    def setUp(self):
        self.case = matrix.cases()[0]
        self.before = dict(playerState='PLAYING', effectiveRtspTransport='tcp', videoCodec='hevc', audioCodec='aac',
                           videoFrameCount=20, renderedFrameCount=20, audioPacketCount=20,
                           audioSinkWriteCount=0, audioWorkerRunning=False, audioEnabled=False,
                           fdCount=100, threadCount=20, nativeHeapBytes=1000)
        self.events = [self.event('fault', 0), self.event('heartbeat', 0),
                       self.event('event', 100, event='reconnect_disconnected'),
                       self.event('event', 110, event='reconnecting', attempt=1, delayMs=1000),
                       self.event('heartbeat', 1000), self.event('restored', 1100),
                       self.event('event', 1200, event='reconnect_success'),
                       self.event('stats', 1300, **dict(self.before, videoFrameCount=30, renderedFrameCount=30)),
                       self.event('heartbeat', 2000),
                       self.event('stats', 2400, **dict(self.before, videoFrameCount=40, renderedFrameCount=40))]

    @staticmethod
    def event(kind, t, **data):
        return dict(kind=kind, t=t, data=data)

    def evaluate(self):
        return matrix.evaluate_round(self.case, self.events, self.before)

    def test_matrix_unique_cartesian_product(self):
        self.assertEqual(len(matrix.cases()), 32)
        self.assertEqual(len({tuple(c.values()) for c in matrix.cases()}), 32)

    def test_evaluator_measures_device_time(self):
        r = self.evaluate()
        self.assertEqual(r['status'], 'PASS')
        self.assertEqual(r['disconnectDetectedMs'], 100)
        self.assertEqual(r['reconnectElapsedMs'], 100)

    def test_no_synthetic_pass_without_fault(self):
        self.events = [e for e in self.events if e['kind'] != 'fault']
        self.assertEqual(self.evaluate()['status'], 'FAIL')

    def test_404_requires_waiting_source_evidence(self):
        self.case['faultType'] = 'source_404'
        self.assertEqual(self.evaluate()['status'], 'FAIL')
        self.events.insert(4, self.event('event', 900, event='waiting_source', source404=True))
        self.assertEqual(self.evaluate()['status'], 'PASS')

    def test_transport_fallback_is_not_tcp_pass(self):
        self.events[-1]['data']['effectiveRtspTransport'] = 'udp'
        self.assertEqual(self.evaluate()['status'], 'FAIL')

    def test_audio_off_worker_is_failure(self):
        self.events[-1]['data']['audioWorkerRunning'] = True
        self.assertEqual(self.evaluate()['status'], 'FAIL')

    def test_audio_on_needs_new_generation_and_advancing_clock(self):
        self.case['audio'] = True
        self.before['audioQueueGeneration'] = 1
        for i, e in enumerate(self.events):
            if e['kind'] == 'stats':
                e['data'].update(audioEnabled=True, audioPlayable=True, audioWorkerRunning=True,
                                 audioPlaybackClockValid=True, audioSinkWriteCount=i,
                                 audioPlaybackClockUs=i*1000, audioQueueGeneration=2, audioClockGeneration=2)
        self.assertEqual(self.evaluate()['status'], 'PASS')
        self.events[-1]['data']['audioClockGeneration'] = 1
        self.assertEqual(self.evaluate()['status'], 'FAIL')

    def test_backoff_and_heartbeat(self):
        self.events[3]['data']['delayMs'] = 1
        self.assertIn('backoff', self.evaluate()['failureReason'])
        self.events = [e for e in self.events if e['kind'] != 'heartbeat']
        self.assertIn('heartbeat', self.evaluate()['failureReason'])

    def test_video_and_resource_failures(self):
        self.events[-1]['data'].update(renderedFrameCount=20, fdCount=150)
        self.assertFalse(self.evaluate()['videoRecovered'])
        self.assertIn('resource growth', self.evaluate()['failureReason'])

    def test_recording_requires_audio_packets_and_no_errors(self):
        self.case['recording'] = True
        self.before['recorder'] = dict(packetsWritten=10, audioPacketCount=5, completedSegmentCount=0)
        self.events[-1]['data']['recorder'] = dict(recording=True, packetsWritten=50, audioPacketCount=0,
                                                  completedSegmentCount=1, writeErrors=0, queueDrops=0)
        self.assertFalse(self.evaluate()['recordingRecovered'])
        self.events[-1]['data']['recorder']['audioPacketCount'] = 20
        self.assertTrue(self.evaluate()['recordingRecovered'])
        self.events[-1]['data']['recorder']['queueDrops'] = 1
        self.assertFalse(self.evaluate()['recordingRecovered'])

    def test_recording_requires_segment_rotation(self):
        self.case['recording'] = True
        self.before['recorder'] = dict(packetsWritten=10, audioPacketCount=5, completedSegmentCount=1)
        self.events[-1]['data']['recorder'] = dict(recording=True, packetsWritten=50, audioPacketCount=20,
                                                  completedSegmentCount=1, writeErrors=0, queueDrops=0)
        self.assertFalse(self.evaluate()['recordingRecovered'])
        self.events[-1]['data']['recorder']['completedSegmentCount'] = 2
        self.assertTrue(self.evaluate()['recordingRecovered'])

    def test_sdp_without_aac_packets_is_not_baseline_pass(self):
        self.assertEqual(matrix.baseline_errors(self.case, self.before), [])
        self.before['audioPacketCount'] = 0
        self.assertTrue(matrix.baseline_errors(self.case, self.before))

    def test_reports_and_url_redaction(self):
        self.assertNotIn('password', matrix.redact('open rtsp://user:password@host/path failed'))
        with tempfile.TemporaryDirectory() as directory:
            matrix.write_report(Path(directory), [matrix.initial_result(c, 'no real device run') for c in matrix.cases()])
            data = json.loads((Path(directory)/'results.json').read_text())
            self.assertEqual(sum(r['status'] == 'NOT_RUN' for r in data), 32)
            self.assertTrue(all(r['passFail'] == 'NOT_RUN' for r in data))


if __name__ == '__main__':
    unittest.main()
