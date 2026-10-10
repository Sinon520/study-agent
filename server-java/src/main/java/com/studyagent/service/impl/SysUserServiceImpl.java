package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.SysUser;
import com.studyagent.mapper.SysUserMapper;
import com.studyagent.service.SysUserService;
import org.springframework.stereotype.Service;

/**
 * 用户 业务实现
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {
}