part of '../allcheck_ui.dart';

class _StartupLogo extends StatefulWidget {
  const _StartupLogo({required this.child});

  final Widget child;

  @override
  State<_StartupLogo> createState() => _StartupLogoState();
}

class _StartupLogoState extends State<_StartupLogo> {
  bool _ready = false;
  bool _finished = false;

  @override
  void initState() {
    super.initState();
    unawaited(_waitForFirstFrame());
  }

  Future<void> _waitForFirstFrame() async {
    await WidgetsBinding.instance.waitUntilFirstFrameRasterized;
    await Future<void>.delayed(const Duration(milliseconds: 250));
    if (mounted) setState(() => _ready = true);
  }

  @override
  Widget build(BuildContext context) {
    final showLogo = !_finished && !MediaQuery.disableAnimationsOf(context);
    return Stack(
      fit: StackFit.expand,
      children: [
        ExcludeSemantics(
          excluding: showLogo,
          child: IgnorePointer(ignoring: showLogo, child: widget.child),
        ),
        if (showLogo)
          ColoredBox(
            color: Colors.white,
            child: !_ready
                ? null
                : Center(
                    child: TweenAnimationBuilder<double>(
                      tween: Tween(begin: 0, end: 1),
                      duration: const Duration(milliseconds: 900),
                      curve: Curves.easeInOutCubic,
                      onEnd: () => setState(() => _finished = true),
                      builder: (context, progress, logo) => ClipPath(
                        clipper: _ClockwiseLogoClipper(progress),
                        child: logo,
                      ),
                      child: Image.asset(
                        'assets/branding/startup_logo.png',
                        package: 'allcheck_ui',
                        width: 144,
                        height: 144,
                        fit: BoxFit.contain,
                        semanticLabel: '올체크 로고',
                      ),
                    ),
                  ),
          ),
      ],
    );
  }
}

class _ClockwiseLogoClipper extends CustomClipper<Path> {
  const _ClockwiseLogoClipper(this.progress);

  final double progress;

  @override
  Path getClip(Size size) {
    final bounds = Offset.zero & size;
    if (progress >= 1) return Path()..addRect(bounds);
    final center = bounds.center;
    final radius = center.distance + 1;
    return Path()
      ..moveTo(center.dx, center.dy)
      ..arcTo(
        Rect.fromCircle(center: center, radius: radius),
        -math.pi / 2,
        progress * 2 * math.pi,
        false,
      )
      ..close();
  }

  @override
  bool shouldReclip(_ClockwiseLogoClipper oldClipper) =>
      oldClipper.progress != progress;
}
