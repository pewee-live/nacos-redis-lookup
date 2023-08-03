
# Nacos - 使用redis来启动集群!!


[原始说明文档](README1.md)
-------
本改造适用于有redis的环境,nacos原始的集群启动方式为ServerAddress和ConfigFile方式,需要额外部署服务器或者在每个实例下放置配置文件比较麻烦;
因此我们添加了一种redis的 集群启动方式;只需要通过配置 nacos.core.member.lookup.type=redis  来通过redis启动集群;