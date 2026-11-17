#!/usr/bin/env python3
"""Actual Android GPS API -> accident store -> nearby app check on one explicitly selected AVD.

The coordinates come from emulator geo fix, not a road journey. Two package owners run
sequentially on this AVD; server registration and receiver observation share one event id.
"""
import argparse
import importlib.util
import json
import pathlib
import time

spec = importlib.util.spec_from_file_location('legacy_qa', pathlib.Path(__file__).with_name('verify-prototype-v06.py'))
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--user', type=int, default=10)
    parser.add_argument('--adb', type=pathlib.Path, default=pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb')
    parser.add_argument('--output', type=pathlib.Path, default=pathlib.Path('output/accident-location-qa/screens'))
    args = parser.parse_args()
    with qa.Harness(args) as h:
        for package in [h.A, h.B]:
            for permission in ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION']:
                h.adb('shell', 'pm', 'grant', '--user', args.user, package, 'android.permission.' + permission)
        def layout(package):
            h.launch(package)
            h.adb('shell', 'am', 'start', '--user', args.user, '-n', 'ai.umos.maps.android.navigation.app/.MainActivity')
            time.sleep(1)
            h.adb('shell', 'input', 'swipe', 2546, 720, 1706, 720, 2000)
            time.sleep(1)
        def gps(package, latitude):
            h.adb('emu', 'geo', 'fix', '127.0946', str(latitude))
            h.op(package, 'geo_live')
            return h.wait(package, lambda s: s.get('geoLocation') is not None and
                s['geoLocation']['origin'] == 'GPS' and abs(s['geoLocation']['latitude'] - latitude) < .00001, timeout=30)
        def capture(name, package):
            for mode in ['DARK', 'LIGHT']:
                h.op(package, 'theme', value=mode)
                time.sleep(.55)
                h.snapshot(name + '-' + mode.lower(), package)
            h.op(package, 'theme', value='DARK')
            print('Captured', name, flush=True)
        def seats(driver, passenger):
            for area, value in [('driver', driver), ('passenger', passenger), ('rear_left', False), ('rear_right', False)]:
                h.console('/vehicle/seat', kind='occupied', area=area, value=str(value).lower())
            h.wait(h.A, lambda s: any(x['area'] == 1 and x['occupied'] == driver for x in s['seats']) and
                any(x['area'] == 4 and x['occupied'] == passenger for x in s['seats']))

        h.adb('shell', 'am', 'force-stop', '--user', args.user, h.B)
        layout(h.A)
        h.op(h.A, 'reset'); h.op(h.A, 'online', value=True)
        h.op(h.A, 'receiver_enabled', value=True); h.op(h.A, 'consent', value=False)
        h.wait(h.A, lambda s: s.get('incidentId') is None and s.get('receiverEnabled') and not s.get('consent'))
        gps(h.A, 37.4127)
        seats(True, True)
        h.console('/vehicle/body', door_fl=0)
        h.wait(h.A, lambda s: not s.get('restricted') and s.get('geoReceiverStatus') == 'ACTIVE')
        time.sleep(3)
        s = h.state(h.A)
        assert s['incidentId'] is None and s['accidentSharing'] == 'IDLE'
        capture('01-normal-stop', h.A)

        h.op(h.A, 'consent', value=True); h.op(h.A, 'online', value=False)
        h.console('/vehicle/gear', gear='Drive')
        h.console('/vehicle/controls', speed=90, accelerator=0, brake=0)
        h.wait(h.A, lambda s: s['consent'] and s['restricted'])
        h.op(h.A, 'scenario', collision='REAR', voice='off')
        moving = h.wait(h.A, lambda s: s.get('incidentId') is not None and s['restricted'] and s['accidentSharing'] == 'QUEUED', timeout=30)
        event_id = moving['incidentId']
        frozen = moving['accidentLocation']
        assert frozen['origin'] == 'GPS' and abs(frozen['latitude'] - 37.4127) < .00001
        capture('02-accident-moving-pending', h.A)

        h.console('/vehicle/controls', speed=0, accelerator=0, brake=0)
        h.console('/vehicle/gear', gear='Park')
        h.wait(h.A, lambda s: s['supportQualified'] and not s['restricted'] and s['accidentSharing'] == 'QUEUED')
        capture('03-accident-stopped-pending', h.A)
        h.op(h.A, 'online', value=True)
        registered = h.wait(h.A, lambda s: s['accidentSharing'] == 'REGISTERED' and s['incidentId'] == event_id)
        assert registered['accidentLocation'] == frozen
        assert not registered['nearbyAccidents']  # own event excluded
        capture('04-accident-registered', h.A)

        h.console('/vehicle/body', door_fl=1)
        seats(False, True)
        capture('05-passenger-remains', h.A)
        h.op(h.A, 'transcript', text='움직이기 어려워요')
        h.wait(h.A, lambda s: s.get('help') == 'NEEDS_HELP')
        capture('06-help-needed', h.A)
        gps(h.A, 37.4277)
        s = h.wait(h.A, lambda s: s.get('accidentLocation') == frozen)
        assert s['accidentLocation'] == frozen
        h.save('immutable-location', {'eventId': event_id, 'registeredLocation': frozen, 'laterVehicleLocation': s['geoLocation']})

        # Second app owner, actual GPS API at a different emulator position.
        h.console('/vehicle/body', door_fl=0)
        layout(h.B)
        h.op(h.B, 'reset'); h.op(h.B, 'consent', value=False)
        h.op(h.B, 'receiver_enabled', value=True); h.op(h.B, 'online', value=True)
        h.wait(h.B, lambda s: s.get('incidentId') is None and s.get('receiverEnabled'))
        gps(h.B, 37.4154)
        received = h.wait(h.B, lambda s: any(n['id'] == event_id and n['freshness'] == 'FRESH' for n in s['nearbyAccidents']))
        match = next(n for n in received['nearbyAccidents'] if n['id'] == event_id)
        assert 290 < match['distanceMeters'] < 310 and match['eventType'] == 'ACCIDENT'
        capture('07-nearby-same-accident', h.B)
        h.op(h.B, 'online', value=False)
        h.wait(h.B, lambda s: any(n['id'] == event_id and n['freshness'] == 'UNCONFIRMED' for n in s['nearbyAccidents']))
        capture('08-connection-unconfirmed', h.B)
        h.op(h.B, 'online', value=True)
        gps(h.B, 37.4277)
        h.wait(h.B, lambda s: s['geoReceiverStatus'] == 'ACTIVE' and not s['nearbyAccidents'])
        capture('09-outside-radius', h.B)
        gps(h.B, 37.4154)
        h.wait(h.B, lambda s: any(n['id'] == event_id and n['freshness'] == 'FRESH' for n in s['nearbyAccidents']))
        h.save('result', {'passed': True, 'eventId': event_id, 'distanceMeters': match['distanceMeters'],
            'registeredLocation': frozen, 'scope': 'Android GPS API with emulator coordinates; owner A/Pleos to owner B/demo on one AVD. Foreground local durable server, radial distance, no road direction matching or real crash detection.'})
        print('PASS event', event_id, 'distance', match['distanceMeters'], flush=True)


if __name__ == '__main__':
    main()
