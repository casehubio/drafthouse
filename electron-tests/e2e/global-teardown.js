// electron-tests/e2e/global-teardown.js
'use strict';

module.exports = async function globalTeardown() {
  const pid = parseInt(process.env.TEST_QUARKUS_PID, 10);
  if (!pid || isNaN(pid)) return;
  try {
    process.kill(pid, 'SIGTERM');
  } catch (_) {
    // Process already exited — nothing to clean up
  }
};
