"""Keep compiler/test diagnostics visible even when dependency warnings flood logs."""
from pathlib import Path
import os
import xml.etree.ElementTree as ET


def annotation(level, title, value):
    value = value[:3500].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::{level} title={title}::{value}')


log = Path('gradle-build.log')
if log.exists():
    lines = log.read_text(errors='replace').splitlines()
    relevant = [s for s in lines if not s.startswith('WARNING:') and
                (s.startswith('e: ') or ' FAILED' in s or s.startswith('* What went wrong:') or
                 'error:' in s.lower() or 'Exception' in s or 'Unresolved reference' in s)]
    for line in relevant[-25:]:
        annotation('error', 'Android diagnostic', line)

count = failures = errors = 0
for path in Path('app/build/test-results').glob('**/TEST-*.xml'):
    suite = ET.parse(path).getroot()
    count += int(suite.get('tests', 0)); failures += int(suite.get('failures', 0)); errors += int(suite.get('errors', 0))
    for case in suite.findall('testcase'):
        for failure in list(case.findall('failure')) + list(case.findall('error')):
            annotation('error', case.get('name', 'Unit test'), failure.get('message', '') + '\n' + (failure.text or ''))
if count:
    annotation('notice', 'Android unit tests', f'{count} tests, {failures} failures, {errors} errors')
    if 'GITHUB_STEP_SUMMARY' in os.environ:
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as f:
            f.write(f'\nAndroid unit tests: {count}; failures: {failures}; errors: {errors}.\n')
for path in Path('app/build/reports').glob('lint-results-*.txt'):
    text = path.read_text(errors='replace')
    for section in text.split('\n\n'):
        if 'Error:' in section:
            annotation('error', 'Android lint', section)
