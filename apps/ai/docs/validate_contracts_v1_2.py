import importlib.metadata
import json
from pathlib import Path
import subprocess

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate
from pglast import ast, parse_sql

root = Path(__file__).resolve().parents[3]
docs_dir = root / 'apps/ai/docs'
source_sha = '341a01597af2d697cd53906fb27ed8c4b7e44072'
source = yaml.safe_load(subprocess.check_output(
    ['git', 'show', source_sha + ':docs/api/openapi.yaml'], cwd=root).decode('utf-8'))
ai = yaml.safe_load((docs_dir / 'ai-api-v1.2.yaml').read_text(encoding='utf-8'))
callback = yaml.safe_load((docs_dir / 'backend-callback-v1.2.yaml').read_text(encoding='utf-8'))
checks = []

def record(name, condition):
    assert condition, name
    checks.append(name)

for name, doc in [('AI', ai), ('callback', callback)]:
    validate(doc)
    record(name + ': OpenAPI 3.1.1 structure', doc['openapi'] == '3.1.1')
    record(name + ': origin-only server', all('/api/v1' not in s['url'] for s in doc['servers']))
    for shared in ['Id', 'DateTime', 'WorkType', 'ReceptionDraftFields', 'Error']:
        record(name + ': exact PR7 ' + shared,
               doc['components']['schemas'][shared] == source['components']['schemas'][shared])

def validate_case(schema, value, valid=True, doc=ai):
    wrapper = {'components': doc['components'], '$ref': '#/components/schemas/' + schema}
    errors = list(Draft202012Validator(wrapper, format_checker=FormatChecker()).iter_errors(value))
    record(schema + ': ' + ('accept ' if valid else 'reject ') + str(len(checks)), not errors if valid else bool(errors))

validate_case('HealthResponse', {'status': 'ok'})
validate_case('HealthResponse', {'status': 'bad'}, False)
for value, valid in [('', False), ('x', True), ('x' * 64, True), ('x' * 65, False), (1, False), (None, False)]:
    validate_case('Id', value, valid)
for work_type in ['EXTERIOR_LEAK', 'INTERIOR_LEAK', 'BLOCKAGE']:
    validate_case('WorkType', work_type)
validate_case('WorkType', 'UNKNOWN', False)
validate_case('ReceptionDraftFields', {})
for field, maximum in [('symptoms', 2000), ('address', 300), ('notes', 2000)]:
    validate_case('ReceptionDraftFields', {field: '가' * maximum})
    validate_case('ReceptionDraftFields', {field: '가' * (maximum + 1)}, False)
    validate_case('ReceptionDraftFields', {field: None}, False)
validate_case('ReceptionDraftFields', {'symptoms': ''}, False)
validate_case('ReceptionDraftFields', {'notes': ''})
validate_case('ReceptionDraftFields', {'workType': None}, False)
validate_case('ReceptionDraftFields', {'confidence': 0.9}, False)
for value, valid in [('2026-11-06T14:00:00+09:00', True), ('2026-11-06T05:00:00Z', True),
                     ('2026-11-06T14:00:00', False), ('2026-11-06', False)]:
    validate_case('DateTime', value, valid)
error = {'code': 'STT_FAILED', 'message': '전사 실패', 'traceId': 'trace_example'}
validate_case('Error', error)
validate_case('Error', {'code': 'STT_FAILED', 'message': '전사 실패'}, False)
validate_case('Error', {**error, 'detail': []}, False)
validate_case('Error', {**error, 'fieldErrors': [{'field': 'file'}]}, False)
identity = {'jobId': 'job_example', 'receptionId': 'rec_example', 'receptionVersion': 1,
            'recordingFileId': 'file_example'}
for status in ['PENDING', 'PROCESSING']:
    validate_case('SttJobView', {**identity, 'status': status})
    validate_case('SttJobView', {**identity, 'status': status, 'transcript': None}, False)
    validate_case('SttJobView', {**identity, 'status': status, 'error': error}, False)
validate_case('SttJobView', {**identity, 'status': 'SUCCEEDED', 'transcript': '가' * 100000})
validate_case('SttJobView', {**identity, 'status': 'SUCCEEDED', 'transcript': '가' * 100001}, False)
validate_case('SttJobView', {**identity, 'status': 'SUCCEEDED'}, False)
validate_case('SttJobView', {**identity, 'status': 'SUCCEEDED', 'transcript': '원문', 'error': error}, False)
validate_case('SttJobView', {**identity, 'status': 'FAILED', 'error': error})
validate_case('SttJobView', {**identity, 'status': 'FAILED'}, False)
validate_case('SttJobView', {**identity, 'status': 'FAILED', 'error': None}, False)
validate_case('SttJobView', {**identity, 'status': 'NOT_AVAILABLE'}, False)
validate_case('TranscribeRequest', {**identity, 'file': 'binary-placeholder'})
validate_case('TranscribeRequest', identity, False)
validate_case('TranscribeRequest', {**identity, 'file': 'binary-placeholder', 'receptionVersion': 0}, False)
validate_case('TranscribeAccepted', {'jobId': 'job_example', 'receptionId': 'rec_example', 'status': 'PENDING'})
validate_case('TranscribeAccepted', {'jobId': 'job_example', 'receptionId': 'rec_example', 'status': 'SUCCEEDED'}, False)
analyzed = {'jobId': 'job_example', 'receptionId': 'rec_example', 'receptionVersion': 1,
            'status': 'SUCCEEDED', 'aiOriginal': {},
            'missingFields': ['workType', 'symptoms', 'address', 'desiredVisitFrom', 'desiredVisitTo']}
validate_case('AnalyzeSucceeded', analyzed)
validate_case('AnalyzeSucceeded', {**analyzed, 'aiOriginal': None}, False)
validate_case('AnalyzeSucceeded', {**analyzed, 'missingFields': ['notes']}, False)
validate_case('AnalyzeSucceeded', {**analyzed, 'missingFields': ['address', 'address']}, False)
for status in ['SUCCEEDED', 'FAILED', 'PENDING']:
    validate_case('SttCallback', {'jobId': 'job_example', 'receptionId': 'rec_example',
                  'recordingFileId': 'file_example', 'status': status}, status != 'PENDING', callback)

idempotency = ai['components']['parameters']['IdempotencyKey']['schema']
for value, valid in [('', False), ('k', True), ('k' * 200, True), ('k' * 201, False)]:
    record('Idempotency-Key boundary ' + str(len(value)),
           Draft202012Validator(idempotency).is_valid(value) == valid)
ids = []
example_count = 0
for doc in [ai, callback]:
    for route, operations in doc['paths'].items():
        for method, operation in operations.items():
            ids.append(operation['operationId'])
            record('path not in PR7 ' + route, route not in source['paths'])
            for container in [operation.get('requestBody', {}), *operation['responses'].values()]:
                for media in container.get('content', {}).values():
                    examples = ([media['example']] if 'example' in media else [])
                    examples += [v['value'] for v in media.get('examples', {}).values() if 'value' in v]
                    for example in examples:
                        schema_name = media['schema']['$ref'].split('/')[-1]
                        validate_case(schema_name, example, doc=doc)
                        example_count += 1
record('unique operationIds', len(ids) == len(set(ids)) == 5)
record('health public only', ai['paths']['/health']['get']['security'] == [])
record('202 STT response', '202' in ai['paths']['/internal/v1/transcribe']['post']['responses'])

sql_path = root / 'apps/backend/src/main/resources/db/migration/V1__create_ai_job_tables.sql.draft'
sql = sql_path.read_text(encoding='utf-8')
statements = parse_sql(sql)
tables = [x.stmt for x in statements if isinstance(x.stmt, ast.CreateStmt)]
indexes = [x.stmt for x in statements if isinstance(x.stmt, ast.IndexStmt)]
record('SQL creates only two expected tables', {x.relation.relname for x in tables} == {'ai_stt_jobs', 'ai_webhook_deliveries'})
record('SQL only CREATE TABLE/INDEX', len(tables) + len(indexes) == len(statements))
expected_columns = {
    'ai_stt_jobs': ['job_id','idempotency_key','request_hash','reception_id','reception_version','recording_file_id','status','transcript','error_code','error_message','retry_count','created_at','updated_at','completed_at'],
    'ai_webhook_deliveries': ['delivery_id','job_id','event_type','status','attempt_count','next_retry_at','last_http_status','last_error','created_at','delivered_at']}
for table in tables:
    record('SQL columns ' + table.relation.relname,
           [x.colname for x in table.tableElts if isinstance(x, ast.ColumnDef)] == expected_columns[table.relation.relname])
record('draft not default SQL suffix', sql_path.suffix == '.draft')
record('draft version unique locally', not list(sql_path.parent.glob('V1__*.sql')))
for document in docs_dir.glob('*.md'):
    import re
    for link in re.findall(r'\]\(([^)]+)\)', document.read_text(encoding='utf-8')):
        if not link.startswith(('http:', 'https:', '#')):
            record('local document link ' + link, (document.parent / link.split('#')[0]).exists())
print(json.dumps({'checks_passed': len(checks), 'examples_validated': example_count,
                  'sql_statements': len(statements), 'sql_tables': len(tables), 'sql_indexes': len(indexes),
                  'tools': {n: importlib.metadata.version(n) for n in ['openapi-spec-validator','jsonschema','pglast']}}, ensure_ascii=False, indent=2))
