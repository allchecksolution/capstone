import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:http/http.dart' as http;

import 'api_endpoint.dart';
import 'backend.dart';

/// Adapter for the Controllers actually merged into main. Other domains must
/// supply their implemented contracts before their operations can be enabled.
class SpringFrontendBackend implements FrontendBackend {
  SpringFrontendBackend(String baseUrl, {http.Client? client})
    : _base = ApiEndpoint(baseUrl).origin,
      _client = client ?? http.Client();

  final Uri _base;
  final http.Client _client;
  String? _token;
  int _generation = 0;
  final _uploads = <String, _RecordingAttempt>{};
  bool get hasServiceSession => _token != null;

  /// Called by the service authentication adapter once that domain is merged.
  /// Never pass a Kakao provider token as the application's service token.
  void setServiceSession(String accessToken) {
    if (accessToken.trim().isEmpty) throw ArgumentError('Empty service token');
    clearSession();
    _token = accessToken;
  }

  @override
  void clearSession() {
    _token = null;
    _generation++;
    _uploads.clear();
  }

  void close() {
    clearSession();
    _client.close();
  }

  static const workTypes = <String, String>{
    'EXTERIOR_LEAK': '외벽 누수',
    'INTERIOR_LEAK': '내벽 누수',
    'BLOCKAGE': '막힘',
    'BOILER': '보일러',
    'WATERPROOFING': '방수',
  };

  @override
  Future<ApiData> call(String operation, [ApiData data = const {}]) async {
    final generation = _generation;
    final result = await _call(operation, data);
    _checkSession(generation);
    return result;
  }

  Future<ApiData> _call(String operation, ApiData data) async {
    final id = data['id'];
    String receptionPath() => '/api/v1/receptions/${_segment(id)}';
    switch (operation) {
      case 'receptions/list':
        final result = await _json(
          'GET',
          '/api/v1/receptions',
          query: {
            'page': '${data['page'] ?? 1}',
            'size': '${data['size'] ?? 20}',
            if (data['reviewStatus'] != null)
              'reviewStatus': '${data['reviewStatus']}',
          },
        );
        final rows = result['items'] as List;
        return {
          'consultations': rows.map((r) => _project(r as ApiData)).toList(),
          'receptionPage': result['page'],
        };
      case 'receptions/detail':
        return {'consultation': _project(await _json('GET', receptionPath()))};
      case 'receptions/create':
        final result = await _json(
          'POST',
          '/api/v1/receptions',
          key: _key(data),
          body: {
            if (data['callerPhone'] != null) 'callerPhone': data['callerPhone'],
            if (data['callStartedAt'] != null)
              'callStartedAt': data['callStartedAt'],
          },
        );
        return {
          'consultation': _project(result),
          'resourceId': result['receptionId'],
        };
      case 'receptions/review':
        final fields = data['fields'] as ApiData;
        final workType = workTypes.entries
            .where((e) => e.value == fields['type'])
            .firstOrNull
            ?.key;
        if (workType == null) {
          throw const ServiceFailure(400, '작업 유형을 선택해 주세요.');
        }
        final from = DateTime.tryParse('${fields['desiredVisitFrom']}');
        final to = DateTime.tryParse('${fields['desiredVisitTo']}');
        if (from == null ||
            to == null ||
            !to.isAfter(from) ||
            !to.isAfter(DateTime.now())) {
          throw const ServiceFailure(
            400,
            '방문 시작·종료 시각을 확인해 주세요. 종료 시각은 미래여야 합니다.',
          );
        }
        await _json(
          'POST',
          '${receptionPath()}/confirmation',
          key: _key(data),
          body: {
            'expectedVersion': data['expectedVersion'],
            'finalData': {
              'workType': workType,
              'symptoms': fields['symptom'],
              'address': fields['address'],
              'contactPhone': fields['phone'],
              'desiredVisitFrom': from.toUtc().toIso8601String(),
              'desiredVisitTo': to.toUtc().toIso8601String(),
            'notes': fields['notes'] ?? '',
            },
          },
        );
        return call('receptions/detail', {'id': id});
      case 'receptions/reopen':
        await _json(
          'POST',
          '${receptionPath()}/reopen',
          key: _key(data),
          body: {'expectedVersion': data['expectedVersion']},
        );
        return call('receptions/detail', {'id': id});
      case 'receptions/analysis':
        return _json(
          'GET',
          '${receptionPath()}/analyses/${_segment(data['analysisRequestId'])}',
        );
      case 'receptions/recording-access':
        return _json(
          'GET',
          '${receptionPath()}/recordings/${_segment(data['recordingFileId'])}/access',
        );
      case 'receptions/recording-upload':
        return _upload(data);
      default:
        throw const ServiceFailure(501, '현재 서버 버전에서 이 기능의 API 연결을 지원하지 않습니다.');
    }
  }

  static String _segment(Object? value) {
    if (value is! String ||
        value.isEmpty ||
        value.length > 64 ||
        !RegExp(r'^[A-Za-z0-9_-]+$').hasMatch(value)) {
      throw const ServiceFailure(400, '접수 식별자를 확인해 주세요.');
    }
    return Uri.encodeComponent(value);
  }

  static String _key(ApiData data) {
    final key = data['requestId'];
    if (key is! String || key.isEmpty || key.length > 180) {
      throw const ServiceFailure(400, '요청 식별자를 확인해 주세요.');
    }
    return key;
  }

  Future<ApiData> _json(
    String method,
    String path, {
    ApiData? body,
    String? key,
    Map<String, String>? query,
  }) async {
    final token = _token;
    if (token == null) throw const ServiceFailure(401, '서버 인증 연결이 필요합니다.');
    final generation = _generation;
    try {
      final request =
          http.Request(
              method,
              _base.replace(path: path, queryParameters: query),
            )
            ..followRedirects = false
            ..headers.addAll({
              'Accept': 'application/json',
              'Authorization': 'Bearer $token',
              if (body != null) 'Content-Type': 'application/json',
              'Idempotency-Key': ?key,
            });
      if (body != null) request.body = jsonEncode(body);
      final response = await http.Response.fromStream(
        await _client.send(request).timeout(const Duration(seconds: 30)),
      ).timeout(const Duration(seconds: 30));
      _checkSession(generation);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        if (response.statusCode == 401) clearSession();
        throw _failure(response.statusCode);
      }
      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      if (decoded is! ApiData) throw const FormatException();
      return decoded;
    } on ServiceFailure {
      rethrow;
    } on TimeoutException {
      throw const ServiceFailure(0, '응답 시간이 초과되었습니다. 같은 작업을 다시 시도해 주세요.');
    } catch (_) {
      throw const ServiceFailure(0, '서버 응답을 확인하지 못했습니다. 연결 상태를 확인해 주세요.');
    }
  }

  void _checkSession(int generation) {
    if (generation != _generation) {
      throw const ServiceFailure(499, '요청이 취소되었습니다.');
    }
  }

  static ServiceFailure _failure(int status) =>
      ServiceFailure(status, switch (status) {
        400 => '입력 형식과 필수 항목을 확인해 주세요.',
        401 => '인증이 만료되었습니다. 다시 로그인해 주세요.',
        403 => '이 작업에 접근할 권한이 없습니다.',
        404 => '접수를 찾을 수 없거나 접근할 수 없습니다.',
        409 => '접수 상태가 변경되었습니다. 새로고침 후 확인해 주세요.',
        415 => 'WAV 녹음 파일만 업로드할 수 있습니다.',
        503 => '서버 인증 또는 저장소 연결이 준비되지 않았습니다.',
        _ => '요청을 처리하지 못했습니다. 다시 시도해 주세요.',
      });

  Future<ApiData> _upload(ApiData data) async {
    final id = _segment(data['id']);
    final bytes = data['bytes'] as Uint8List;
    final filename = data['originalFilename'] as String;
    if (bytes.isEmpty ||
        !filename.toLowerCase().endsWith('.wav') ||
        filename.contains('/') ||
        filename.contains('\\') ||
        filename.length > 255) {
      throw const ServiceFailure(415, '원본 파일명을 유지한 WAV 파일을 선택해 주세요.');
    }
    final digest = base64Encode(sha256.convert(bytes).bytes);
    final attempt = _uploads.putIfAbsent(
      '$id/$filename/$digest',
      () => _RecordingAttempt(
        key: _key(data),
        expectedVersion: data['expectedVersion'] as int,
      ),
    );
    final generation = _generation;
    final path = '/api/v1/receptions/$id/recordings';
    if (!attempt.completed) {
      if (!attempt.uploaded) {
        // Same body/key on retry. The server replays the reservation and issues a
        // fresh URL, rather than creating another reception or recording.
        final grant = await _json(
          'POST',
          data['recordingFileId'] == null
              ? '$path/upload-requests'
              : '$path/${_segment(data['recordingFileId'])}/upload-requests',
          key: attempt.key,
          body: data['recordingFileId'] != null
              ? null
              : {
                  'expectedVersion': attempt.expectedVersion,
                  'originalFilename': filename,
                  'contentType': 'audio/wav',
                  'sizeBytes': bytes.length,
                  'checksum': {'algorithm': 'SHA256', 'value': digest},
                },
        );
        final url = Uri.parse(grant['uploadUrl'] as String);
        if (url.scheme != 'https' ||
            url.host.isEmpty ||
            url.userInfo.isNotEmpty ||
            grant['method'] != 'PUT') {
          throw const ServiceFailure(0, '녹음 업로드 주소를 확인하지 못했습니다.');
        }
        final headers = Map<String, String>.from(
          grant['requiredHeaders'] as Map,
        );
        final signed = {
          for (final e in headers.entries) e.key.toLowerCase(): e.value,
        };
        if (signed['content-length'] != '${bytes.length}' ||
            signed['x-amz-checksum-sha256'] != digest) {
          throw const ServiceFailure(
            400,
            '예약한 원본 녹음과 다른 파일입니다. 같은 파일을 선택해 주세요.',
          );
        }
        if (headers.keys.any(
          (k) => ['authorization', 'cookie'].contains(k.toLowerCase()),
        )) {
          throw const ServiceFailure(0, '녹음 업로드 헤더를 확인하지 못했습니다.');
        }
        _checkSession(generation);
        try {
          final request = http.Request('PUT', url)
            ..followRedirects = false
            ..bodyBytes = bytes;
          request.headers.addAll(headers);
          // No application bearer on the S3 request; preserve signed headers.
          final response = await _client
              .send(request)
              .timeout(const Duration(minutes: 2));
          await response.stream.drain<void>().timeout(
            const Duration(seconds: 30),
          );
          _checkSession(generation);
          if ((response.statusCode < 200 || response.statusCode >= 300) &&
              response.statusCode != 412) {
            throw const ServiceFailure(0, '녹음 업로드에 실패했습니다. 같은 파일로 다시 시도해 주세요.');
          }
        } on ServiceFailure {
          rethrow;
        } catch (_) {
          throw const ServiceFailure(0, '녹음을 전송하지 못했습니다. 같은 파일로 다시 시도해 주세요.');
        }
        attempt.recordingFileId = grant['recordingFileId'] as String;
        attempt.uploadVersion = grant['version'] as int;
        attempt.uploaded = true;
      }
      // A retry may encounter 412 after a successful PUT with a lost response.
      // Completion checks MIME, size and SHA256 on the server before acceptance.
      await _json(
        'POST',
        '$path/${_segment(attempt.recordingFileId)}/completion',
        key: '${attempt.key}/complete',
        body: {'expectedVersion': attempt.uploadVersion},
      );
      attempt.completed = true;
    }
    return call('receptions/detail', {'id': data['id']});
  }

  static ApiData _project(ApiData row) {
    final fields = row['finalData'] as ApiData? ?? {};
    final review = row['reviewStatus'];
    final failed = [
      'recordingStatus',
      'sttStatus',
      'transcriptStorageStatus',
      'analysisStatus',
    ].any((key) => row[key] == 'FAILED');
    return {
      ...row,
      'id': row['receptionId'],
      'status': row['requestId'] != null
          ? '의뢰 등록'
          : review == 'CONFIRMED'
          ? '검토 완료'
          : failed
          ? '분석 실패'
          : row['analysisStatus'] == 'SUCCEEDED'
          ? '분석 완료'
          : row['recordingStatus'] == 'NOT_AVAILABLE'
          ? '접수 대기'
          : '분석 중',
      'reviewed': review == 'CONFIRMED',
      'jobId': row['requestId'],
      'stt': row['transcript'],
      'analysis': row['aiOriginal'],
      'type': workTypes[fields['workType']] ?? '',
      'symptom': fields['symptoms'] ?? '',
      'address': fields['address'] ?? '',
      'phone': fields['contactPhone'] ?? '',
      'desiredVisitFrom': fields['desiredVisitFrom'] ?? '',
      'desiredVisitTo': fields['desiredVisitTo'] ?? '',
      'notes': fields['notes'] ?? '',
    };
  }
}

class _RecordingAttempt {
  _RecordingAttempt({required this.key, required this.expectedVersion});
  final String key;
  final int expectedVersion;
  String? recordingFileId;
  int? uploadVersion;
  bool uploaded = false;
  bool completed = false;
}
