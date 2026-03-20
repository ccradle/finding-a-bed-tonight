###############################################################################
# Lite Environment — PostgreSQL only ($15-30/month target)
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
      tier        = "lite"
      managed_by  = "terraform"
      environment = "lite"
    }
  }
}

###############################################################################
# Network
###############################################################################

module "network" {
  source = "../../modules/network"

  tier       = "lite"
  aws_region = var.aws_region
}

###############################################################################
# Application (deployed first to get security group ID for database)
###############################################################################

module "app" {
  source = "../../modules/app"

  tier               = "lite"
  aws_region         = var.aws_region
  vpc_id             = module.network.vpc_id
  public_subnet_ids  = module.network.public_subnet_ids
  private_subnet_ids = module.network.private_subnet_ids

  backend_image  = var.backend_image
  frontend_image = var.frontend_image

  db_jdbc_url = module.postgres.jdbc_url
  db_username = module.postgres.app_role_username
  db_password = var.db_app_password

  spring_profiles_active = "prod,lite"
  cors_origins           = var.cors_origins
  jwt_secret             = var.jwt_secret
}

###############################################################################
# PostgreSQL
###############################################################################

module "postgres" {
  source = "../../modules/postgres"

  tier                  = "lite"
  vpc_id                = module.network.vpc_id
  private_subnet_ids    = module.network.private_subnet_ids
  app_security_group_id = module.app.app_security_group_id

  db_master_username = var.db_master_username
  db_master_password = var.db_master_password
  db_app_password    = var.db_app_password
}

###############################################################################
# Cost Guardrail — $50/month billing alarm
###############################################################################

resource "aws_cloudwatch_metric_alarm" "cost_guardrail" {
  alarm_name          = "fabt-lite-monthly-cost"
  alarm_description   = "Alert when estimated monthly charges exceed $50 for FABT Lite tier"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "EstimatedCharges"
  namespace           = "AWS/Billing"
  period              = 86400
  statistic           = "Maximum"
  threshold           = 50
  treat_missing_data  = "notBreaching"

  alarm_actions = [var.alert_sns_topic_arn]

  dimensions = {
    Currency = "USD"
  }

  tags = {
    project = "finding-a-bed-tonight"
    tier    = "lite"
    purpose = "cost-guardrail"
  }
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
