part of 'backend.dart';

String _resource(Object? value) {
  if (value is! String || !RegExp(r'^[A-Za-z0-9_-]+$').hasMatch(value)) {
    throw const ServiceFailure(400, '식별자를 확인해 주세요.');
  }
  return value;
}

extension _WorkflowSession on HttpFrontendBackend {
  Future<WebSocket?> openRoom(String rawDriver) async {
    final driver = int.tryParse(rawDriver);
    final bearer = _accessToken;
    if (driver == null || driver <= 0 || bearer == null) return null;
    final uri = Uri.parse('$baseUrl/chat/ws');
    final socket = await WebSocket.connect(
      uri.replace(scheme: uri.scheme == 'https' ? 'wss' : 'ws').toString(),
      protocols: ['allcheck-chat.v1'],
      headers: {'Authorization': 'Bearer $bearer'},
    );
    if (_accessToken != bearer) {
      await socket.close();
      return null;
    }
    socket.add(jsonEncode({'type': 'SUBSCRIBE', 'driverId': driver}));
    return socket;
  }

  Future<ApiData> device(String operation, ApiData data) {
    final path = 'push-devices/${_resource(data['installationId'])}';
    if (operation == 'devices/unregister')
      return requestJson(operation, null, path: path, method: 'DELETE');
    return requestJson(
      operation,
      {
        'platform': (data['platform'] as String).toUpperCase(),
        'pushToken': data['token'],
      },
      path: path,
      method: 'PUT',
    );
  }

  Future<ApiData> uploadPhoto(ApiData data) async {
    final generation = sessionGeneration;
    final bytes = data['bytes'] as List<int>;
    final work = _resource(data['workId']);
    final key = data['requestId'] as String;
    final path = 'works/$work/photos';
    final grant = await requestJson(
      'work/photo',
      {
        'phase': data['phase'],
        'originalFilename': data['filename'],
        'contentType': data['contentType'],
        'sizeBytes': bytes.length,
        'checksum': {
          'algorithm': 'SHA256',
          'value': base64Encode(sha256.convert(bytes).bytes),
        },
      },
      path: '$path/upload-requests',
      method: 'POST',
      headers: {'Idempotency-Key': key},
    );
    final uri = Uri.parse(grant['uploadUrl'] as String);
    if (uri.scheme != 'https' ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        grant['method'] != 'PUT' ||
        grant['workId'] != work ||
        grant['phase'] != data['phase']) {
      throw const ServiceFailure(0, '사진 업로드 예약을 확인해 주세요.');
    }
    final put = http.Request('PUT', uri)
      ..followRedirects = false
      ..bodyBytes = bytes;
    for (final entry in (grant['requiredHeaders'] as Map).entries) {
      final name = entry.key as String;
      if (['authorization', 'cookie'].contains(name.toLowerCase()))
        throw const ServiceFailure(0, '사진 업로드 헤더가 올바르지 않습니다.');
      put.headers[name] = (entry.value as List).cast<String>().join(',');
    }
    final uploaded = await sendUpload(put).timeout(const Duration(minutes: 2));
    await uploaded.stream.drain<void>().timeout(const Duration(seconds: 30));
    if (generation != sessionGeneration)
      throw const ServiceFailure(499, '요청이 취소되었습니다.');
    if ((uploaded.statusCode < 200 || uploaded.statusCode >= 300) &&
        uploaded.statusCode != 412)
      throw const ServiceFailure(0, '사진 업로드를 다시 시도해 주세요.');
    final photo = _resource(grant['photoFileId']);
    await requestJson(
      'work/photo',
      null,
      path: '$path/$photo/completion',
      method: 'POST',
      headers: {'Idempotency-Key': '$key/complete'},
    );
    return {'photoFileId': photo};
  }
}
