###############################################################################
# PostgreSQL Module — Variables
# Finding A Bed Tonight
###############################################################################

variable "tier" {
  description = "Deployment tier: lite, standard, or full"
  type        = string
  validation {
    condition     = contains(["lite", "standard", "full"], var.tier)
    error_message = "tier must be one of: lite, standard, full"
  }
}

variable "vpc_id" {
  description = "VPC ID where the RDS instance will be created"
  type        = string
}

variable "private_subnet_ids" {
  description = "List of private subnet IDs for the DB subnet group"
  type        = list(string)
}

variable "app_security_group_id" {
  description = "Security group ID of the application (ECS tasks)"
  type        = string
}

variable "db_master_username" {
  description = "Master username for the RDS instance"
  type        = string
  sensitive   = true
}

variable "db_master_password" {
  description = "Master password for the RDS instance"
  type        = string
  sensitive   = true
}

variable "db_app_password" {
  description = "Password for the fabt_app non-superuser role"
  type        = string
  sensitive   = true
}

variable "database_name" {
  description = "Name of the database to create"
  type        = string
  default     = "fabt"
}

variable "tags" {
  description = "Additional tags to apply to all resources"
  type        = map(string)
  default     = {}
}
