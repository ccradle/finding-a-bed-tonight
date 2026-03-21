###############################################################################
# [BOOTSTRAP] — S3 Backend + DynamoDB Lock Table
# Finding A Bed Tonight
#
# Run this ONCE before any environment deployment:
#   cd infra/terraform/bootstrap
#   terraform init
#   terraform apply
#
# This creates the remote state infrastructure that all environments use.
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
      project    = "finding-a-bed-tonight"
      managed_by = "terraform"
      purpose    = "bootstrap"
    }
  }
}

variable "aws_region" {
  description = "AWS region for the state backend"
  type        = string
  default     = "us-east-1"
}

###############################################################################
# S3 Bucket — Terraform State
###############################################################################

resource "aws_s3_bucket" "terraform_state" {
  bucket = "fabt-terraform-state"

  tags = {
    Name = "fabt-terraform-state"
  }

  # Prevent accidental deletion of state
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

###############################################################################
# DynamoDB Table — Terraform State Locks
###############################################################################

resource "aws_dynamodb_table" "terraform_locks" {
  name                        = "fabt-terraform-locks"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "LockID"
  deletion_protection_enabled = true

  attribute {
    name = "LockID"
    type = "S"
  }

  server_side_encryption {
    enabled = true
  }

  point_in_time_recovery {
    enabled = true
  }

  tags = {
    Name = "fabt-terraform-locks"
  }
}

###############################################################################
# Outputs
###############################################################################

output "state_bucket_name" {
  description = "Name of the S3 bucket for Terraform state"
  value       = aws_s3_bucket.terraform_state.id
}

output "state_bucket_arn" {
  description = "ARN of the S3 bucket for Terraform state"
  value       = aws_s3_bucket.terraform_state.arn
}

output "lock_table_name" {
  description = "Name of the DynamoDB table for state locks"
  value       = aws_dynamodb_table.terraform_locks.name
}
