###############################################################################
# Redis Module — ElastiCache Redis 7 Cluster
# Finding A Bed Tonight
# Only used in Standard and Full tiers
###############################################################################

locals {
  common_tags = merge(var.tags, {
    project = "finding-a-bed-tonight"
    tier    = var.tier
    module  = "redis"
  })

  node_type = var.tier == "standard" ? "cache.t3.micro" : "cache.t3.small"
}

###############################################################################
# Security Group
###############################################################################

resource "aws_security_group" "redis" {
  name_prefix = "fabt-${var.tier}-redis-"
  description = "Allow Redis access from application security group"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from app"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.app_security_group_id]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-redis-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

###############################################################################
# Subnet Group
###############################################################################

resource "aws_elasticache_subnet_group" "redis" {
  name       = "fabt-${var.tier}-redis"
  subnet_ids = var.private_subnet_ids

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-redis-subnet-group"
  })
}

###############################################################################
# ElastiCache Redis Cluster (single node, no replication for cost)
###############################################################################

resource "aws_elasticache_cluster" "redis" {
  cluster_id      = "fabt-${var.tier}-redis"
  engine          = "redis"
  engine_version  = "7.0"
  node_type       = local.node_type
  num_cache_nodes = 1
  port            = 6379

  subnet_group_name  = aws_elasticache_subnet_group.redis.name
  security_group_ids = [aws_security_group.redis.id]

  parameter_group_name = "default.redis7"

  snapshot_retention_limit = var.tier == "full" ? 3 : 0

  maintenance_window = "sun:05:00-sun:06:00"

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-redis"
  })
}
