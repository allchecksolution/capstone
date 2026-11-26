part of '../allcheck_ui.dart';

extension _AdminAccountScreens on _WorkflowShellState {
  bool get canManageAdministrators =>
      !developmentSession && s.account?['isBootstrapAdmin'] == true;

  Future<void> administratorTask(Future<void> Function() task) async {
    if (busy) return;
    refresh(() => busy = true);
    try {
      await task();
    } catch (error) {
      if (error is ServiceFailure &&
          error.status == 409 &&
          page.startsWith('admin-')) {
        adminAccountRequest = null;
        if (mounted) go('admin-accounts', replace: true);
      }
      failure(error);
    } finally {
      if (mounted) refresh(() => busy = false);
    }
  }

  Future<void> openServerDriver(String driverId) async {
    if (widget.backend is! AdminRestBackend || developmentSession) {
      go('driver', item: driverId);
      return;
    }
    await administratorTask(() async {
      final result = await api.call('drivers/detail', {'id': driverId});
      if (!mounted) return;
      await applyServerState(result);
      if (mounted) go('driver', item: driverId);
    });
  }

  Future<void> loadDriverPage(int next) async {
    await administratorTask(() async {
      final result = await api.call('drivers/list', {'page': next});
      if (mounted) await applyServerState(result);
    });
  }

  Future<void> openDriverList() async {
    await loadDriverPage(1);
    if (mounted && signedIn) go('drivers');
  }

  Widget administratorAccounts() {
    if (!canManageAdministrators) return notice('최초 관리자만 계정을 관리할 수 있습니다.');
    adminAccountRequest ??= api.call('admin-accounts/list', {
      'page': adminAccountPage,
    });
    return FutureBuilder<Json>(
      future: adminAccountRequest,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return stack([
            notice(
              snapshot.error is ServiceFailure
                  ? (snapshot.error as ServiceFailure).message
                  : '관리자 목록을 불러오지 못했습니다.',
              error: true,
            ),
            button('다시 시도', () => refresh(() => adminAccountRequest = null)),
          ]);
        }
        final result = snapshot.data!;
        return stack([
          heading('관리자 계정 관리'),
          button('관리자 추가', () {
            controllerForAdminForm();
            go('admin-create');
          }),
          for (final account in records(result['items']))
            box([
              heading(text(account, 'loginId')),
              kv('계정 상태', account['enabled'] == true ? '활성' : '비활성'),
              if (account['isBootstrapAdmin'] == true)
                notice('최초 관리자 · 탈퇴 및 비활성화 불가')
              else if (account['enabled'] == true)
                Wrap(
                  spacing: 12,
                  children: [
                    button('수정', () {
                      editingAdministrator = account;
                      go('admin-edit', item: text(account, 'accountId'));
                      controller('loginId', text(account, 'loginId')).text =
                          text(account, 'loginId');
                      controller('password').clear();
                    }, secondary: true),
                    button('삭제', () async {
                      if (!await confirm(
                        '${text(account, 'loginId')} 계정을 비활성화할까요? 기존 로그인은 모두 종료됩니다.',
                      )) {
                        return;
                      }
                      await administratorTask(() async {
                        await api.call('admin-accounts/deactivate', {
                          'accountId': account['accountId'],
                          'expectedVersion': account['version'],
                        });
                        refresh(() => adminAccountRequest = null);
                        message('계정을 비활성화했습니다.');
                      });
                    }, danger: true),
                  ],
                ),
            ]),
          Wrap(
            spacing: 12,
            children: [
              if (adminAccountPage > 1)
                button(
                  '이전 페이지',
                  () => refresh(() {
                    adminAccountPage--;
                    adminAccountRequest = null;
                  }),
                  secondary: true,
                ),
              Text('$adminAccountPage 페이지'),
              if (adminAccountPage * 20 < (result['totalElements'] as int))
                button(
                  '다음 페이지',
                  () => refresh(() {
                    adminAccountPage++;
                    adminAccountRequest = null;
                  }),
                  secondary: true,
                ),
            ],
          ),
          button(
            '새로고침',
            () => refresh(() => adminAccountRequest = null),
            secondary: true,
          ),
        ]);
      },
    );
  }

  void controllerForAdminForm() {
    fields['admin-create//loginId']?.clear();
    fields['admin-create//password']?.clear();
  }

  Widget administratorForm({required bool creating}) {
    if (!canManageAdministrators) return notice('최초 관리자만 계정을 관리할 수 있습니다.');
    return form([
      heading(creating ? '관리자 추가' : '관리자 계정 수정'),
      field('loginId', '로그인 ID'),
      field(
        'password',
        creating ? '초기 비밀번호' : '새 비밀번호',
        required: creating,
        helperText: creating
            ? '첫 로그인 시 변경을 강제하지 않습니다.'
            : '비워 두면 기존 비밀번호를 유지합니다. 변경 시 기존 로그인은 종료됩니다.',
      ),
      button(creating ? '계정 생성' : '변경 저장', () async {
        if (!(formKey.currentState?.validate() ?? false)) return;
        await administratorTask(() async {
          final password = controller('password').text;
          await api.call(
            creating ? 'admin-accounts/create' : 'admin-accounts/update',
            {
              'loginId': controller('loginId').text,
              if (creating || password.isNotEmpty) 'password': password,
              if (!creating) ...{
                'accountId': editingAdministrator!['accountId'],
                'expectedVersion': editingAdministrator!['version'],
              },
            },
          );
          controller('password').clear();
          adminAccountRequest = null;
          if (mounted) go('admin-accounts', replace: true);
          message(creating ? '관리자 계정을 생성했습니다.' : '계정 정보를 변경했습니다.');
        });
      }),
    ]);
  }

  Widget administratorPassword() => form([
    heading('내 비밀번호 변경'),
    field('currentPassword', '현재 비밀번호'),
    field('password', '새 비밀번호'),
    small('변경하면 모든 기기의 로그인이 종료됩니다.'),
    button('비밀번호 변경', () async {
      if (!(formKey.currentState?.validate() ?? false)) return;
      await administratorTask(() async {
        await api.call('admin-accounts/password', {
          'currentPassword': controller('currentPassword').text,
          'newPassword': controller('password').text,
        });
        controller('currentPassword').clear();
        controller('password').clear();
        await closeSession();
        if (mounted) {
          history.clear();
          go('login', replace: true);
          message('새 비밀번호로 다시 로그인해 주세요.');
        }
      });
    }),
  ]);
}
