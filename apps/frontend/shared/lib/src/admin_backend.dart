part of 'backend.dart';

/// Administrator screens use the existing REST contracts, never local approvals.
class AdminRestBackend implements FrontendBackend {
  AdminRestBackend(
    String baseUrl, {
    http.Client? client,
    FrontendBackend Function(HttpFrontendBackend)? receptionsFactory,
  }) : _session = HttpFrontendBackend(baseUrl, client: client, restAuth: true) {
    _receptions = receptionsFactory?.call(_session);
  }
  final HttpFrontendBackend _session;
  late final FrontendBackend? _receptions;
  bool get supportsReceptions => _receptions != null;
  ApiData? _user;
  List<ApiData> _drivers = [];
  int _page = 1;
  int _totalPages = 0;
  String? _detailId;
  int _generation = 0;

  @override
  void clearSession() {
    _generation++;
    _user = null;
    _drivers = [];
    _page = 1;
    _totalPages = 0;
    _detailId = null;
    _session.clearSession();
    _receptions?.clearSession();
  }

  void close() {
    clearSession();
    _session.close();
  }

  @override
  Future<ApiData> call(String operation, [ApiData data = const {}]) async {
    if (operation == 'auth/admin-login') clearSession();
    final generation = _generation;
    if (operation.startsWith('receptions/') && _receptions != null) {
      final result = await _receptions!.call(operation, data);
      _check(generation);
      return result;
    }
    switch (operation) {
      case 'auth/admin-login':
        final result = await _session._request(operation, {
          'loginId': data['username'],
          'password': data['password'],
        }, path: 'auth/sessions/admin');
        _check(generation);
        _drivers = [];
        _detailId = null;
        _page = 1;
        _totalPages = 0;
        _user = Map<String, dynamic>.from(result['user'] as Map);
        if (_user?['userType'] != 'ADMIN') {
          clearSession();
          throw const ServiceFailure(403, '관리자 계정으로 로그인해 주세요.');
        }
        return {
          'state': _state(),
          'name': data['username'],
          'resetState': true,
        };
      case 'auth/logout':
        try {
          if (_session._refreshToken != null) {
            await _session.call(operation, {
              'refreshToken': _session._refreshToken,
            });
          }
          return {};
        } finally {
          clearSession();
        }
      case 'workflow/state':
      case 'drivers/list':
        _detailId = operation == 'workflow/state'
            ? data['driverId']?.toString()
            : null;
        _page = (data['page'] as int?) ?? _page;
        final me = await _session._request(
          'auth/me',
          {},
          path: 'auth/accounts/me',
          method: 'GET',
        );
        final list = await _session._request(
          'drivers/list',
          {},
          path: 'drivers?page=$_page&size=20',
          method: 'GET',
        );
        _check(generation);
        _user = me;
        _drivers = (list['items'] as List)
            .map((e) => _driver(Map<String, dynamic>.from(e as Map)))
            .toList();
        _totalPages = (list['page'] as Map)['totalPages'] as int;
        if (_detailId != null && operation == 'workflow/state') {
          await _detail(_detailId!, generation);
        }
        return {'state': _state()};
      case 'drivers/detail':
        await _detail(data['id'].toString(), generation);
        return {'state': _state()};
      case 'drivers/decide':
        final id = _id(data['id']);
        await _session._request(
          operation,
          {
            'expectedVersion': data['expectedVersion'],
            'decision': data['approve'] == true ? 'APPROVE' : 'REJECT',
            if (data['approve'] != true) 'rejectionReason': data['reason'],
          },
          path: 'drivers/$id/approval',
          headers: {'Idempotency-Key': data['requestId'] as String},
        );
        _check(generation);
        await _detail(id, generation);
        return {'state': _state()};
      case 'admin-accounts/list':
        return _session._request(
          operation,
          {},
          path: 'admin-accounts?page=${data['page'] ?? 1}&size=20',
          method: 'GET',
        );
      case 'admin-accounts/create':
        return _session._request(operation, {
          'loginId': data['loginId'],
          'initialPassword': data['password'],
        }, path: 'admin-accounts');
      case 'admin-accounts/update':
        return _session._request(
          operation,
          {
            'expectedVersion': data['expectedVersion'],
            if (data['loginId'] != null) 'loginId': data['loginId'],
            if (data['password'] != null) 'newPassword': data['password'],
          },
          path: 'admin-accounts/${_id(data['accountId'])}',
          method: 'PUT',
        );
      case 'admin-accounts/deactivate':
        return _session._request(
          operation,
          {},
          path:
              'admin-accounts/${_id(data['accountId'])}?expectedVersion=${data['expectedVersion']}',
          method: 'DELETE',
        );
      case 'admin-accounts/password':
        final result = await _session._request(
          operation,
          data,
          path: 'admin-accounts/me/password',
          method: 'PUT',
        );
        clearSession();
        return result;
      case 'notifications/list':
        return {'items': <ApiData>[]};
      default:
        throw const ServiceFailure(501, '이 화면은 아직 서버에 연결되지 않았습니다.');
    }
  }

  void _check(int generation) {
    if (generation != _generation) {
      throw const ServiceFailure(499, '요청이 취소되었습니다.');
    }
  }

  String _id(dynamic value) {
    final id = value.toString();
    if (!RegExp(r'^[1-9][0-9]*$').hasMatch(id)) {
      throw const ServiceFailure(400, '계정 식별자를 확인해 주세요.');
    }
    return id;
  }

  Future<void> _detail(String rawId, int generation) async {
    final id = _id(rawId);
    final result = await _session._request(
      'drivers/detail',
      {},
      path: 'drivers/$id',
      method: 'GET',
    );
    _check(generation);
    _detailId = id;
    final mapped = _driver(result);
    _drivers = [..._drivers.where((e) => e['id'] != id), mapped];
  }

  ApiData _driver(ApiData value) {
    final profile = Map<String, dynamic>.from(value['profile'] as Map? ?? {});
    final business = Map<String, dynamic>.from(value['business'] as Map? ?? {});
    final rejections = (value['rejectionHistory'] as List? ?? [])
        .map((row) => Map<String, dynamic>.from(row as Map))
        .toList();
    return {
      'id': value['driverId'].toString(),
      'version': value['version'],
      'status': switch (value['accountStatus']) {
        'PENDING_APPROVAL' => '승인 대기',
        'ACTIVATED' => '활성',
        'REJECTED' => '반려',
        'WITHDRAWN' => '탈퇴',
        _ => '알 수 없음',
      },
      'name': profile['workerName'] ?? value['workerName'],
      'phone': profile['phone'],
      'email': profile['email'],
      'company': business['businessName'] ?? value['businessName'],
      'owner': business['representativeName'],
      'businessNumber': business['businessRegistrationNumber'],
      'companyAddress': business['companyAddress'],
      'specialties': (profile['specialties'] as List? ?? [])
          .map(
            (code) =>
                DriverOnboardingBackend._workTypes.entries
                    .where((entry) => entry.value == code)
                    .firstOrNull
                    ?.key ??
                code.toString(),
          )
          .toList(),
      'regions': profile['activityRegions'],
      'equipment': profile['equipment'],
      'businessVerified':
          (value['businessVerification'] as Map?)?['status'] == 'VERIFIED',
      'rejectionHistory': value['rejectionHistory'],
      'reason': value['accountStatus'] == 'REJECTED' && rejections.isNotEmpty
          ? rejections.last['reason']
          : null,
    };
  }

  ApiData _state() => {
    'schema': 1,
    'account': _user,
    'draft': <String, dynamic>{},
    'jobs': <ApiData>[],
    'drivers': _drivers,
    'consultations': <ApiData>[],
    'reports': <String, dynamic>{},
    'cancellations': <ApiData>[],
    'threads': <String, dynamic>{},
    'messages': <ApiData>[],
    'role': 'editor',
    'driverPage': _page,
    'driverTotalPages': _totalPages,
  };
}
