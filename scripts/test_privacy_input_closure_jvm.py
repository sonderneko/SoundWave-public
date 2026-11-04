from pathlib import Path
import subprocess,sys
import tempfile, shutil
r=Path(__file__).resolve().parents[1]; h=Path(tempfile.mkdtemp(prefix='soundwave-privacy-closure-')); b=r/'app/src/main/java/xyz/cncnlp/soundwave'
for source in (r/'scripts/privacy-closure-jvm').glob('*.kt'): shutil.copyfile(source, h/source.name)
cp=':'.join(map(str,Path('/tmp/r657-security-jvm').glob('*.jar')))+':/tmp/r657-migu-jvm/coroutines.jar'
# Actual Android-bound consumers compiled against explicit, deterministic facades. No Android build.
sources=list(h.glob('*.kt'))
subject = b/'CrashGuard.kt'
(h/'CrashGuardSubject.kt').write_text(subject.read_text().split('class SoundWaveApplication')[0].replace('import xyz.cncnlp.soundwave.migration.LegacyPlatformAuthCleanup','').replace('import xyz.cncnlp.soundwave.migration.LegacyUnassignedSnapshot','').replace('import xyz.cncnlp.soundwave.startup.StartupTempFileCleaner',''))
main=(b/'MainActivity.kt').read_text()
(h/'ActivitySubject.kt').write_text('package xyz.cncnlp.soundwave\nimport android.content.Intent\nimport android.os.Bundle\n'+main[main.index('class MainActivity :'):main.index('    override fun onResume()')]+ '}\n')
sources=list(h.glob('*.kt'))+[b/'CrashReportRedactor.kt',r/'app/src/test/java/xyz/cncnlp/soundwave/BackupPreferenceProjectionTest.kt']
for name in ['BackupPreferenceProjection.kt','SchemeActionHolder.kt']:
 if (b/name).exists(): sources.append(b/name)
sources.append(b/'BackupStore.kt')
p=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,'-d',str(h/'classes'),*map(str,sources)])
if p.returncode:sys.exit(p.returncode)
names=[a for a in sys.argv[1:] if not a.startswith('--')] or ['BackupPrivacyTest', 'ActivityDeliveryTest', 'BackupPreferenceProjectionTest']
sys.exit(subprocess.run(['java','-cp',str(h/'classes')+':'+cp,'org.junit.runner.JUnitCore',*['xyz.cncnlp.soundwave.'+n for n in names]]).returncode)
