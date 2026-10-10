<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <el-icon :size="30" color="#409eff"><Reading /></el-icon>
        <h1>备考学习 Agent</h1>
        <p>个性化学习计划 · RAG 资料问答 · 错题智能归类</p>
      </div>

      <el-tabs v-model="activeTab" stretch>
        <!-- ------------------------------ 登录 -->
        <el-tab-pane label="登录" name="login">
          <el-form
            ref="loginFormRef"
            :model="loginForm"
            :rules="loginRules"
            label-position="top"
            @keyup.enter="onLogin"
          >
            <el-form-item label="账号" prop="username">
              <el-input v-model="loginForm.username" placeholder="请输入用户名" clearable>
                <template #prefix><el-icon><User /></el-icon></template>
              </el-input>
            </el-form-item>

            <el-form-item label="密码" prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                placeholder="请输入密码"
                show-password
              >
                <template #prefix><el-icon><Lock /></el-icon></template>
              </el-input>
            </el-form-item>

            <el-button type="primary" class="submit-btn" :loading="loading" @click="onLogin">
              登录
            </el-button>

            <div class="demo-tip">
              演示账号：<b>demo</b> / <b>123456</b>
              <el-button link type="primary" @click="fillDemo">一键填入</el-button>
            </div>
          </el-form>
        </el-tab-pane>

        <!-- ------------------------------ 注册 -->
        <el-tab-pane label="注册" name="register">
          <el-form
            ref="registerFormRef"
            :model="registerForm"
            :rules="registerRules"
            label-position="top"
          >
            <el-form-item label="账号" prop="username">
              <el-input v-model="registerForm.username" placeholder="4~20 位字母、数字或下划线" clearable />
            </el-form-item>

            <el-form-item label="密码" prop="password">
              <el-input v-model="registerForm.password" type="password" placeholder="6~32 位" show-password />
            </el-form-item>

            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input v-model="registerForm.confirmPassword" type="password" placeholder="再输一次" show-password />
            </el-form-item>

            <el-form-item label="昵称" prop="nickname">
              <el-input v-model="registerForm.nickname" placeholder="选填，默认与账号相同" clearable />
            </el-form-item>

            <el-form-item label="备考类型" prop="examType">
              <el-radio-group v-model="registerForm.examType">
                <el-radio-button value="考研">考研</el-radio-button>
                <el-radio-button value="考公">考公</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <el-button type="primary" class="submit-btn" :loading="loading" @click="onRegister">
              注册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const activeTab = ref('login')
const loading = ref(false)

const loginFormRef = ref()
const registerFormRef = ref()

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
  examType: '考研',
})

const loginRules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度需在 6~64 之间', trigger: 'blur' },
  ],
}

const registerRules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { min: 4, max: 20, message: '账号长度需在 4~20 之间', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z0-9_]+$/,
      message: '只能包含字母、数字和下划线',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度需在 6~32 之间', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator(_rule, value, callback) {
        if (value !== registerForm.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

function fillDemo() {
  loginForm.username = 'demo'
  loginForm.password = '123456'
}

function goAfterLogin() {
  // 登录前想去哪就回哪，没有就回首页
  const redirect = route.query.redirect
  router.replace(typeof redirect === 'string' && redirect ? redirect : '/dashboard')
}

async function onLogin() {
  try {
    await loginFormRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await userStore.login({ username: loginForm.username, password: loginForm.password })
    ElMessage.success('登录成功')
    goAfterLogin()
  } catch (e) {
    // 具体错误已由 axios 拦截器提示
  } finally {
    loading.value = false
  }
}

async function onRegister() {
  try {
    await registerFormRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await userStore.register({
      username: registerForm.username,
      password: registerForm.password,
      nickname: registerForm.nickname || undefined,
      examType: registerForm.examType,
    })
    ElMessage.success('注册成功，正在自动登录…')
    await userStore.login({
      username: registerForm.username,
      password: registerForm.password,
    })
    goAfterLogin()
  } catch (e) {
    // 具体错误已由 axios 拦截器提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: linear-gradient(135deg, #e8f0fe 0%, #f5f7fa 45%, #e6f4ea 100%);
}

.login-card {
  width: 400px;
  padding: 28px 32px 20px;
  background-color: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 30px rgb(0 0 0 / 8%);
}

.login-brand {
  text-align: center;
  margin-bottom: 8px;
}

.login-brand h1 {
  margin: 8px 0 4px;
  font-size: 20px;
  font-weight: 600;
}

.login-brand p {
  margin: 0 0 4px;
  font-size: 12px;
  color: #909399;
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
}

.demo-tip {
  margin-top: 14px;
  font-size: 12px;
  color: #909399;
  text-align: center;
}
</style>
