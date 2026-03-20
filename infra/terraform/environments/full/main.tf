###############################################################################
# Full Environment — PostgreSQL + Redis + Kafka ($100+/month target)
# Finding A Bed Tonight
###############################################################################

terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      project     = "finding-a-bed-tonight"
      tier        = "full"
      managed_by  = "terraform"
      environment = "full"
    }
  }
}

###############################################################################
# Network
###############################################################################

module "network" {
  source = "../../modules/network"

  tier       = "full"
  aws_region = var.aws_region
}

###############################################################################
# Application
###############################################################################

module "app" {
  source = "../../modules/app"

  tier               = "full"
  aws_region         = var.aws_region
  vpc_id             = module.network.vpc_id
  public_subnet_ids  = module.network.public_subnet_ids
  private_subnet_ids = module.network.private_subnet_ids

  backend_image  = var.backend_image
  frontend_image = var.frontend_image

  db_jdbc_url = module.postgres.jdbc_url
  db_username = module.postgres.app_role_username
  db_password = var.db_app_password

  redis_host              = module.redis.endpoint
  redis_port              = module.redis.port
  kafka_bootstrap_servers = module.kafka.bootstrap_brokers

  spring_profiles_active = "prod,full"
  cors_origins           = var.cors_origins
  jwt_secret             = var.jwt_secret
}

###############################################################################
# PostgreSQL
###############################################################################

module "postgres" {
  source = "../../modules/postgres"

  tier                  = "full"
  vpc_id                = module.network.vpc_id
  private_subnet_ids    = module.network.private_subnet_ids
  app_security_group_id = module.app.app_security_group_id

  db_master_username = var.db_master_username
  db_master_password = var.db_master_password
  db_app_password    = var.db_app_password
}

###############################################################################
# Redis
###############################################################################

module "redis" {
  source = "../../modules/redis"

  tier                  = "full"
  vpc_id                = module.network.vpc_id
  private_subnet_ids    = module.network.private_subnet_ids
  app_security_group_id = module.app.app_security_group_id
}

###############################################################################
# Kafka (MSK)
###############################################################################

module "kafka" {
  source = "../../modules/kafka"

  tier                  = "full"
  vpc_id                = module.network.vpc_id
  private_subnet_ids    = module.network.private_subnet_ids
  app_security_group_id = module.app.app_security_group_id
}

###############################################################################
# Outputs
###############################################################################

output "app_url" {
  description = "Application URL"
  value       = module.app.app_url
}

output "alb_dns_name" {
  description = "ALB DNS name"
  value       = module.app.alb_dns_name
}

output "db_endpoint" {
  description = "Database endpoint"
  value       = module.postgres.endpoint
}

output "redis_endpoint" {
  description = "Redis endpoint"
  value       = module.redis.endpoint
}

output "kafka_bootstrap_brokers" {
  description = "Kafka bootstrap broker connection string"
  value       = module.kafka.bootstrap_brokers
}
