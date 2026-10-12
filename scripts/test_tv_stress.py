import unittest
from tv_stress import parse_frames, summarize


class FrameCaptureTest(unittest.TestCase):
    def test_modern_columns_and_repeated_snapshots(self):
        text = 'Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,FrameCompleted,\n0,8,1000000000,1000000000,1016000000,\n'
        frames = parse_frames(text + text)
        self.assertEqual([16.0], list(frames.values()))

    def test_first_draw_incomplete_and_invalid_rows_excluded(self):
        text = ('Flags,IntendedVsync,FrameCompleted,\n'
                '1,100,200,\n0,100,9223372036854775807,\n0,200,100,\n'
                '0,x,y,\n0,100,10000100,\n')
        self.assertEqual([10.0], list(parse_frames(text).values()))

    def test_empty_and_small_capture_never_pass(self):
        self.assertEqual('INSUFFICIENT_DATA', summarize({}, 60)['status'])
        self.assertEqual('INSUFFICIENT_DATA', summarize({1: 1.0}, 60)['status'])

    def test_refresh_rate_changes_frame_budget(self):
        frames = {i: 20.0 for i in range(300)}
        self.assertEqual('FAIL', summarize(frames, 60)['status'])
        self.assertEqual('PASS', summarize(frames, 50)['status'])

    def test_freeze_fails_even_if_percentiles_pass(self):
        frames = {i: 10.0 for i in range(1000)}
        frames[999] = 701.0
        result = summarize(frames, 60)
        self.assertEqual('FAIL', result['status'])
        self.assertEqual(1, result['frames_over_700ms'])


if __name__ == '__main__':
    unittest.main()
