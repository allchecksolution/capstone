// Skip before importing devDependencies, including production-only installs.
if (process.env.CI || process.env.HUSKY === '0' || process.env.NODE_ENV === 'production') {
  process.exit(0);
}

const { default: husky } = await import('husky');
const error = husky();
if (error) {
  console.error(error);
  process.exitCode = 1;
}
