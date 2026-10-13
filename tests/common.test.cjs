const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

let rejectResponse;
let redirect;
const context = {
  axios: {
    defaults: {},
    interceptors: {
      request: {use() {}},
      response: {use(success, failure) { rejectResponse = failure; }}
    }
  },
  sessionStorage: {getItem() { return null; }},
  location: {href: '/'},
  window: {location: {search: ''}},
  setTimeout(callback) { redirect = callback; },
  console: {log() {}},
  Promise
};
vm.runInNewContext(fs.readFileSync('nginx-1.18.0/html/hmdp/js/common.js', 'utf8'), context);

async function messageFor(error) {
  try {
    await rejectResponse(error);
  } catch (message) {
    return message;
  }
  throw new Error('Expected a rejected request');
}

(async () => {
  assert.equal(await messageFor({message: 'Network Error'}), '网络连接失败，请检查网络');
  assert.equal(await messageFor({code: 'ECONNABORTED'}), '请求超时，请稍后重试');
  assert.equal(await messageFor({response: {status: 500}}), '服务器异常，请稍后重试');
  assert.equal(await messageFor({response: {status: 401}}), '请先登录');
  redirect();
  assert.equal(context.location.href, '/login.html');
  console.log('Frontend request error cases passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
