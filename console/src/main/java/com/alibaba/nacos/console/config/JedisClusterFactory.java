package com.alibaba.nacos.console.config;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;
/**
 * JedisCluster的factory对象
 * @author pewee
 *
 */
public class JedisClusterFactory implements FactoryBean<JedisCluster>, InitializingBean { 
	
	static public Logger logger = LoggerFactory.getLogger(JedisClusterFactory.class);

    private String hostAndPort;
    private JedisCluster jedisCluster;  
    private Integer timeout;  
    private Integer maxRedirections;  
    private String passWord;
    private GenericObjectPoolConfig genericObjectPoolConfig;  

    private Pattern p = Pattern.compile("^.+[:]\\d{1,5}\\s*$");  

    @Override  
    public JedisCluster getObject() throws Exception {  
        return jedisCluster;  
    }  

    @Override  
    public Class<? extends JedisCluster> getObjectType() {  
        return (this.jedisCluster != null ? this.jedisCluster.getClass() : JedisCluster.class);  
    }  

    @Override  
    public boolean isSingleton() {  
        return true;  
    }  

    private Set<HostAndPort> parseHostAndPort() throws Exception {  
        try {  
        	
        	if(null == hostAndPort || "".equals(hostAndPort)) {
        		throw new IllegalArgumentException("reids hostAndPort 不合法");
        	}
        	String[] hostAndPortsArr  = hostAndPort.split(",");

            Set<HostAndPort> haps = new HashSet<HostAndPort>();  
            for (String hostport : hostAndPortsArr) {  

                boolean isIpPort = p.matcher(hostport).matches();  

                if (!isIpPort) {  
                    throw new IllegalArgumentException("ip 或 port 不合法");  
                }  
                String[] ipAndPort = hostport.split(":");  

                HostAndPort hap = new HostAndPort(ipAndPort[0], Integer.parseInt(ipAndPort[1]));  
                haps.add(hap);  
            }  
            return haps;  
        } catch (IllegalArgumentException ex) {  
            throw ex;  
        } catch (Exception ex) {  
            throw new Exception("解析 jedis 配置文件失败", ex);  
        }  
    }  

    @Override  
    public void afterPropertiesSet() throws Exception {  
    	Set<HostAndPort> haps = this.parseHostAndPort();  
        if(null == passWord || "".equals(passWord)) {
        	jedisCluster = new JedisCluster(haps, timeout, maxRedirections,genericObjectPoolConfig);  
        } else {
        	jedisCluster = new JedisCluster(haps, timeout, timeout,maxRedirections,passWord,genericObjectPoolConfig);
        } 

    }  

    public void setTimeout(int timeout) {  
        this.timeout = timeout;  
    }  

    public void setMaxRedirections(int maxRedirections) {  
        this.maxRedirections = maxRedirections;  
    }  

    public void setGenericObjectPoolConfig(GenericObjectPoolConfig genericObjectPoolConfig) {  
        this.genericObjectPoolConfig = genericObjectPoolConfig;  
    }

	public void setPassWord(String passWord) {
		this.passWord = passWord;
	}

	public void setHostAndPort(String hostAndPort) {
		this.hostAndPort = hostAndPort;
	}  

}
