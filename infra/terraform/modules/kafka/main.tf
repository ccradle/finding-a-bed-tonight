###############################################################################
# Kafka Module — MSK (Managed Streaming for Kafka) Cluster
# Finding A Bed Tonight
# Only used in the Full tier
###############################################################################

locals {
  common_tags = merge(var.tags, {
    project = "finding-a-bed-tonight"
    tier    = var.tier
    module  = "kafka"
  })
}

###############################################################################
# Security Group
###############################################################################

resource "aws_security_group" "kafka" {
  name_prefix = "fabt-${var.tier}-kafka-"
  description = "Allow Kafka access from application security group"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Kafka plaintext from app"
    from_port       = 9092
    to_port         = 9092
    protocol        = "tcp"
    security_groups = [var.app_security_group_id]
  }

  # Allow inter-broker communication
  ingress {
    description = "Inter-broker communication"
    from_port   = 9092
    to_port     = 9092
    protocol    = "tcp"
    self        = true
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-kafka-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

###############################################################################
# MSK Cluster Configuration
###############################################################################

resource "aws_msk_configuration" "fabt" {
  name              = "fabt-${var.tier}-kafka-config"
  kafka_versions    = ["3.5.1"]
  description       = "FABT Kafka broker configuration"

  server_properties = <<-PROPERTIES
    auto.create.topics.enable=false
    default.replication.factor=2
    min.insync.replicas=1
    num.partitions=3
    log.retention.hours=168
    log.retention.bytes=1073741824
  PROPERTIES
}

###############################################################################
# MSK Cluster (2 brokers, kafka.t3.small, 10GB EBS)
###############################################################################

resource "aws_msk_cluster" "kafka" {
  cluster_name           = "fabt-${var.tier}-kafka"
  kafka_version          = "3.5.1"
  number_of_broker_nodes = 2

  broker_node_group_info {
    instance_type  = "kafka.t3.small"
    client_subnets = var.private_subnet_ids

    storage_info {
      ebs_storage_info {
        volume_size = 10
      }
    }

    security_groups = [aws_security_group.kafka.id]
  }

  configuration_info {
    arn      = aws_msk_configuration.fabt.arn
    revision = aws_msk_configuration.fabt.latest_revision
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "PLAINTEXT"
      in_cluster    = false
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.kafka.name
      }
    }
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-kafka"
  })
}

###############################################################################
# CloudWatch Log Group for Kafka Broker Logs
###############################################################################

resource "aws_cloudwatch_log_group" "kafka" {
  name              = "/fabt/${var.tier}/kafka"
  retention_in_days = 7

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-kafka-logs"
  })
}
