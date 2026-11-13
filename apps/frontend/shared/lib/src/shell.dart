part of '../allcheck_ui.dart';
class WorkflowShell extends StatefulWidget {
  const WorkflowShell({super.key, required this.role, required this.store, this.driverAuth, this.onKakaoAuthenticated, this.backend, this.push});
  final AppRole role; final AppStore store;
  final FrontendBackend? backend;
  final AppPush? push;
  final DriverAuth? driverAuth;
  final Future<Json?> Function(KakaoIdentity)? onKakaoAuthenticated;
  @override State<WorkflowShell> createState()=>_WorkflowShellState();
}
class _WorkflowShellState extends State<WorkflowShell> with WidgetsBindingObserver {
  AppPush? get appPush=>widget.push;
  final pushSubscriptions=<StreamSubscription<dynamic>>[];
  final publicDocuments=<String,Future<Json>>{};final alerts=<AppAlert>[];final seenAlerts=<String>{};final retryKeys=<String,String>{};
  AppAlert? pendingAlert;String lastResourceId="",pushStatus="",locationError="";String? deviceToken;bool registeringToken=false,refreshingState=false,sendingLocation=false,afterAuthRunning=false;int stateVersion=0;
  StreamSubscription<Position>? locationSubscription;
  @override void didChangeAppLifecycleState(AppLifecycleState state){if(state==AppLifecycleState.resumed){unawaited(refreshState());unawaited(afterAuthentication());}}
  AppStore get s=>widget.store; bool get admin=>widget.role==AppRole.admin;
  String page='login', id='', outcomeTitle='', outcomeText='', outcomeTarget='home', outcomeId='', search='', statusFilter='', typeFilter='', driverFilter='', regionFilter='', requestFilter='대기', jobFilter='진행 중', month='';
  int registrationStep=1,period=30;DateTime? fromDate,toDate;
  bool signedIn=false,busy=false,expanded=false,readonly=false,folderConnected=false,developmentSession=false;
  String adminName='';Json? reportDraft;String draftReportId='';KakaoIdentity? kakaoIdentity;
  late final DriverAuth driverAuth=widget.driverAuth??KakaoDriverAuth();
  final formKey=GlobalKey<FormState>();final fields=<String,TextEditingController>{};final selections=<String,Set<String>>{};final history=<Json>[];Timer? clock;Timer? syncClock;
  @override void initState(){super.initState();month=nowIso().substring(0,7);s.addListener(_changed);WidgetsBinding.instance.addObserver(this);unawaited(initializePush());syncClock=Timer.periodic(const Duration(seconds:15),(_){if(WidgetsBinding.instance.lifecycleState==AppLifecycleState.resumed){unawaited(refreshState());}});clock=Timer.periodic(const Duration(seconds:1),(_){if(mounted&&page=='request')setState((){});});}
  void refresh(void Function() change){if(mounted)setState(change);}
  void _changed(){if(mounted)setState((){});}
  @override void dispose(){clock?.cancel();syncClock?.cancel();WidgetsBinding.instance.removeObserver(this);for(final sub in pushSubscriptions){unawaited(sub.cancel());}unawaited(appPush?.dispose());unawaited(locationSubscription?.cancel());s.removeListener(_changed);for(final c in fields.values){c.dispose();}super.dispose();}
  TextEditingController controller(String key,[String value=''])=>fields.putIfAbsent('$page/$id/$key',()=>TextEditingController(text:value));
  String value(String key)=>controller(key).text.trim();
  Json values(Iterable<String> names)=>{for(final key in names)key:value(key)};
  Set<String> choiceValue(String key,[List<String> initial=const []])=>selections.putIfAbsent('$page/$id/$key',()=>initial.toSet());
  void go(String next,{String item='',bool replace=false}){if(!replace&&(page!=next||id!=item))history.add(<String,dynamic>{'page':page,'id':id});setState((){if(next=='login')signedIn=false;page=next;id=item;if(!['export','detail','report','report-edit','report-history','dispatch'].contains(next)){search='';statusFilter='';}});}
  void back(){if(history.isNotEmpty){final last=history.removeLast();setState((){page=text(last,'page','home');id=text(last,'id');});}else{go(signedIn?'home':'login',replace:true);}}
  void message(String text){if(mounted)ScaffoldMessenger.of(context).showSnackBar(SnackBar(content:Text(text)));}
  Future<bool> act(void Function() update,{String operation=''})async{
    if(busy)return false;setState(()=>busy=true);
    try{await s.change(update);return true;}
    catch(e){message(e.toString().replaceFirst('Bad state: ',''));return false;}
    finally{if(mounted)setState(()=>busy=false);}
  }
  Future<bool> submit(void Function() update,{String operation=''})async{
    if(!(formKey.currentState?.validate()??false))return false;
    const routes={'new':'jobs/create','reception':'receptions/review','report-edit':'reports/edit','settings':'drivers/settings'};
    final route=routes[page];
    if(route==null)return act(update,operation:operation);
    final prefix='$page/$id/';
    return command(route,{'id':id,'fields':{for(final e in fields.entries)if(e.key.startsWith(prefix))e.key.substring(prefix.length):e.value.text.trim()},'selections':{for(final e in selections.entries)if(e.key.startsWith(prefix))e.key.substring(prefix.length):e.value.toList()}},update);
  }
  Future<bool> confirm(String title)async=>await showDialog<bool>(context:context,builder:(context)=>AlertDialog(title:Text(title),actions:[TextButton(onPressed:()=>Navigator.pop(context,false),child:const Text('취소')),FilledButton(onPressed:()=>Navigator.pop(context,true),child:const Text('확인'))]))??false;
  void result(String title,{String description='처리 결과를 확인해 주세요.',String target='home',String item=''}){outcomeTitle=title;outcomeText=description;outcomeTarget=target;outcomeId=item;go('result');}
  Widget heading(String title)=>Text(title,style:const TextStyle(fontSize:18,fontWeight:FontWeight.w700));
  Widget small(String text)=>Text(text,style:const TextStyle(fontSize:12,color:Color(0xff788399),height:1.5));
  Widget notice(String text,{bool error=false})=>Container(width:double.infinity,padding:const EdgeInsets.all(14),decoration:BoxDecoration(color:error?const Color(0xfffdebf0):const Color(0xffe8f7fd),borderRadius:BorderRadius.circular(10)),child:Text(text,style:TextStyle(color:error?const Color(0xffbe324b):const Color(0xff258ea2),fontSize:13,height:1.5)));
  Widget box(List<Widget> children)=>Container(width:double.infinity,padding:const EdgeInsets.all(16),decoration:BoxDecoration(border:Border.all(color:const Color(0xffe6ecf2)),borderRadius:BorderRadius.circular(14)),child:stack(children));
  Widget stack(List<Widget> children)=>Column(crossAxisAlignment:CrossAxisAlignment.stretch,children:[for(var i=0;i<children.length;i++)...<Widget>[if(i>0)const SizedBox(height:12),children[i]]]);
  Widget button(String text,VoidCallback fn,{bool secondary=false,bool danger=false})=>secondary?OutlinedButton(onPressed:busy?null:fn,child:Padding(padding:const EdgeInsets.symmetric(vertical:12),child:Text(text))):FilledButton(style:danger?FilledButton.styleFrom(backgroundColor:const Color(0xfff04c62)):null,onPressed:busy?null:fn,child:Text(text));
  Widget link(String title,String target,{String item=''})=>button(title,()=>go(target,item:item),secondary:true);
  Widget kv(String label,Object? content)=>stack([small(label),Text(content?.toString().isNotEmpty==true?content.toString():'미등록',style:const TextStyle(fontWeight:FontWeight.w600))]);
  Widget field(String key,String label,{String initial='',bool required=true,bool lines=false,bool number=false,bool email=false,bool readOnly=false})=>TextFormField(key:ValueKey('$page/$id/$key'),controller:controller(key,initial),obscureText:key=='password',readOnly:readOnly,maxLines:lines?4:1,keyboardType:number?TextInputType.number:email?TextInputType.emailAddress:TextInputType.text,decoration:InputDecoration(labelText:'$label${required?' *':''}'),validator:(v){if(required&&(v??'').trim().isEmpty)return '필수 항목입니다.';if(number&&v?.isNotEmpty==true&&(double.tryParse(v!)==null||double.parse(v)<0))return '0 이상의 숫자를 입력해 주세요.';if(email&&v?.isNotEmpty==true&&!RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$').hasMatch(v!))return '이메일 형식을 확인해 주세요.';return null;});
  Widget dropdown(String key,String label,List<String> options,{String initial='',bool required=false}){final c=controller(key,initial);final selected=options.contains(c.text)?c.text:null;return DropdownButtonFormField<String>(initialValue:selected,isExpanded:true,decoration:InputDecoration(labelText:label),items:options.map((v)=>DropdownMenuItem(value:v,child:Text(v,overflow:TextOverflow.ellipsis))).toList(),onChanged:(v){c.text=v??'';setState((){});},validator:required?(v)=>v==null||v.isEmpty?'선택해 주세요.':null);}
  Widget choices(String key,String label,List<String> options,{List<String> initial=const []}){final selected=choiceValue(key,initial);return stack([heading(label),Wrap(spacing:8,runSpacing:6,children:options.map((v)=>FilterChip(label:Text(v),selected:selected.contains(v),onSelected:busy?null:(yes)=>setState(()=>yes?selected.add(v):selected.remove(v)))).toList())]);}
  Widget form(List<Widget> children)=>Form(key:formKey,child:stack(children));
  Widget menu(String title,String target,{String item='',String subtitle=''})=>ListTile(contentPadding:EdgeInsets.zero,title:Text(title),subtitle:subtitle.isEmpty?null:small(subtitle),trailing:const Icon(Icons.chevron_right),onTap:()=>go(target,item:item));
  Widget empty(String label)=>Padding(padding:const EdgeInsets.symmetric(vertical:28),child:Center(child:small(label)));
  Widget pill(String label)=>Chip(label:Text(label,style:const TextStyle(fontSize:11)),backgroundColor:const Color(0xffe8f7fd),side:BorderSide.none);
  Widget jobCard(Json j)=>box([Row(children:[pill(text(j,'type')),const Spacer(),small(text(j,'status'))]),heading(text(j,'title',text(j,'symptom','작업 의뢰'))),small(text(j,'area',text(j,'address'))),link('상세 보기',j['status']=='응답 대기'?'request':'detail',item:text(j,'id'))]);
  Widget tabs(List<String> options,String selected,void Function(String) change)=>Wrap(spacing:6,runSpacing:6,children:options.map((v)=>ChoiceChip(label:Text(v),selected:v==selected,onSelected:busy?null:(_)=>setState(()=>change(v)))).toList());
  String get title=>({'login':admin?'관리자앱':'기사앱','kakao-connected':'기사앱','home':admin?'작업 이력 및 통계':'홈','jobs':'작업목록','chat':'관리자 문의','more':'더보기','register':'가입 정보 등록','detail':'작업 상세','report':'작업 보고서','receptions':'상담 접수','reception':'접수 상세','dispatch':'배차 요청','drivers':'기사 관리','driver':'기사 정보','reports':'작업 보고서','report-edit':'보고서 수정','report-history':'원본 및 수정 이력','cancellations':'배차 취소 요청','cancellation':'취소 요청 검토','stats':'나의 작업 통계','result':'처리 결과','settings':'설정','requests':'배차 요청 내역','request':'배차 요청','cancel':'취소 사유','revisit':'재방문 사유','withdraw':'회원 탈퇴','recordings':'Softphone 녹음 연결','export':'조회 결과 내보내기','notifications':'알림'})[page]??'올체크';
  Widget publicPage(){
    if(!admin&&page=='register')return this.driverRegistration();
    if(page=='terms'||page=='privacy')return widget.backend==null?notice('문서를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.'):documentPage();
    if(admin)return form([const SizedBox(height:32),heading('관리자앱'),field('username','아이디'),field('password','비밀번호'),button('로그인',loginAdmin)]);
    if(page=='kakao-connected')return stack([const SizedBox(height:60),const Icon(Icons.check_circle_outline,size:76),heading('카카오 계정이 확인되었습니다.'),if(kakaoIdentity?.nickname!=null)Text(kakaoIdentity!.nickname!),button('계속',completeKakaoSignIn),button('로그아웃',logout,secondary:true)]);
    return stack([const SizedBox(height:100),const Center(child:Text('기사앱',style:TextStyle(fontSize:34,fontWeight:FontWeight.bold))),Center(child:small('빠르고 간편한 기사 업무 관리')),const SizedBox(height:100),FilledButton(style:FilledButton.styleFrom(backgroundColor:const Color(0xfffee500),foregroundColor:Colors.black),onPressed:busy?null:signInWithKakao,child:const Text('카카오 로그인')),Wrap(spacing:12,children:[TextButton(onPressed:()=>go('terms'),child:const Text('이용약관')),TextButton(onPressed:()=>go('privacy'),child:const Text('개인정보 처리방침'))])]);
  }
  Future<void> signInWithKakao()async{
    if(busy)return;setState((){busy=true;kakaoIdentity=null;});
    try{final identity=await driverAuth.signIn();if(!mounted||identity==null)return;kakaoIdentity=identity;history.clear();go('kakao-connected',replace:true);}
    catch(e){message(e.toString().replaceFirst('Bad state: ',''));}
    finally{if(mounted)setState(()=>busy=false);}
  }
  Future<void> enterDevelopment()async{
    if(!developmentAccessEnabled||busy)return;
    if(!await act((){
      if(!admin)s.data['account']=<String,dynamic>{'status':'활성',
        'profile':<String,dynamic>{'name':'기사','phone':''}};
    }))return;
    if(!mounted)return;
    developmentSession=true;adminName=admin?'관리자':'';signedIn=true;
    kakaoIdentity=null;history.clear();go('home',replace:true);
  }
  Future<void> completeKakaoSignIn()async{
    final identity=kakaoIdentity;if(identity==null||busy)return;
    final resolve=widget.onKakaoAuthenticated;
    if(resolve==null&&widget.backend==null){message('로그인 서비스를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.');return;}
    setState(()=>busy=true);
    try{
      final response=resolve==null?await api.call('auth/kakao',{'accessToken':identity.accessToken}):null;final account=resolve!=null?await resolve(identity):response!['account'] as Json?;if(!mounted)return;
      // Drop device-only workflow data: it is never a server session or approval.
      await s.change((){s.data=AppStore.empty(AppRole.driver);s.data['account']=account;});
      if(!mounted)return;for(final c in fields.values){c.dispose();}fields.clear();selections.clear();history.clear();
      if(account==null){registrationStep=1;go('register',replace:true);}
      else{signedIn=true;go('home',replace:true);}if(response?['state'] is Json)await applyServerState(response!);
    }catch(_){message('로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.');}
    finally{if(mounted){setState(()=>busy=false);if(signedIn)unawaited(afterAuthentication());}}
  }

  Widget gated(){final rejected=s.account?['status']=='반려';return stack([const SizedBox(height:40),const Icon(Icons.schedule,size:70),heading(rejected?'가입 신청이 반려되었습니다.':'가입 상태를 확인해 주세요.'),notice(rejected?text(s.account!,'reason','정보 보완이 필요합니다.'):s.account?['status']=='탈퇴'?'탈퇴 처리된 계정입니다.':'관리자 검토가 진행 중입니다.'),if(rejected)button('다시 신청하기',()async{if(await act(()=>s.data['draft']=jsonDecode(jsonEncode(s.profile)) as Json)){registrationStep=1;go('register');}}),button('로그아웃',logout,secondary:true)]);}
  Future<void> logout()async{
    if(!await confirm('로그아웃할까요?'))return;
    await platformTask(()async{
      try{await detachDevice();}catch(_){}
      try{if(!admin&&!developmentSession)await driverAuth.signOut();}catch(_){}
      try{if(widget.backend!=null&&!developmentSession)await api.call('auth/logout');}catch(_){}
      await closeSession();
      if(!mounted)return;
      developmentSession=false;
      for(final c in fields.values){c.dispose();}fields.clear();selections.clear();history.clear();go('login',replace:true);
    });
  }
  Widget commonPage(){
    if(page=='result')return stack([const SizedBox(height:60),const Icon(Icons.check_circle_outline,size:76,color:Color(0xff55bbcc)),heading(outcomeTitle),small(outcomeText),link('계속',outcomeTarget,item:outcomeId),link('홈으로 이동','home')]);
    return const SizedBox.shrink();
  }
  @override Widget build(BuildContext context){
    final allowed=signedIn&&(admin||s.account?['status']=='활성');
    final content=page=='notifications'&&signedIn?inbox():page=='result'?commonPage():!signedIn||page=='register'?publicPage():!allowed?gated():page=='result'?commonPage():admin?this.adminPage():this.driverPage();
    final navPages=['home','jobs','chat','more'];final index=page=='chat'?2:['more','settings','recordings','drivers','driver','cancellations','cancellation'].contains(page)?3:['home','stats','notifications'].contains(page)?0:1;final showNav=allowed&&!['register','login'].contains(page);
    final wide=admin&&showNav&&MediaQuery.sizeOf(context).width>900;
    final scroll=Align(alignment:Alignment.topCenter,child:ConstrainedBox(constraints:BoxConstraints(maxWidth:admin?1100:520),child:SingleChildScrollView(padding:const EdgeInsets.all(24),child:stack([if(s.startupWarning.isNotEmpty)notice(s.startupWarning,error:true),if(locationError.isNotEmpty)notice(locationError,error:true),if(developmentAccessEnabled&&page=='login')button('개발용으로 입장',enterDevelopment,secondary:true),content]))));
    return PopScope(canPop:false,onPopInvokedWithResult:(didPop,result){if(!didPop)back();},child:Scaffold(appBar:AppBar(title:Text(title),leading:history.isNotEmpty?IconButton(icon:const Icon(Icons.chevron_left),onPressed:back):null,actions:[if(allowed)IconButton(tooltip:'알림',icon:const Icon(Icons.notifications_outlined),onPressed:(){go('notifications');unawaited(loadInbox());}),if(allowed)IconButton(tooltip:'새로고침',icon:const Icon(Icons.refresh),onPressed:busy?null:refreshState),if(busy)const Padding(padding:EdgeInsets.all(16),child:SizedBox(width:22,height:22,child:CircularProgressIndicator(strokeWidth:2)))]),body:SafeArea(top:false,child:LayoutBuilder(builder:(context,constraints)=>admin&&showNav&&constraints.maxWidth>900?Row(children:[NavigationRail(selectedIndex:index,onDestinationSelected:(i)=>go(navPages[i]),labelType:NavigationRailLabelType.all,destinations:const [NavigationRailDestination(icon:Icon(Icons.home_outlined),label:Text('홈')),NavigationRailDestination(icon:Icon(Icons.assignment_outlined),label:Text('작업목록')),NavigationRailDestination(icon:Icon(Icons.chat_bubble_outline),label:Text('문의')),NavigationRailDestination(icon:Icon(Icons.more_horiz),label:Text('더보기'))]),const VerticalDivider(width:1),Expanded(child:scroll)]):scroll)),bottomNavigationBar:showNav&&!wide?SafeArea(top:false,child:NavigationBar(selectedIndex:index,onDestinationSelected:(i)=>go(navPages[i]),destinations:const [NavigationDestination(icon:Icon(Icons.home_outlined),label:'홈'),NavigationDestination(icon:Icon(Icons.assignment_outlined),label:'작업목록'),NavigationDestination(icon:Icon(Icons.chat_bubble_outline),label:'문의'),NavigationDestination(icon:Icon(Icons.more_horiz),label:'더보기')])):null));
  }
}
