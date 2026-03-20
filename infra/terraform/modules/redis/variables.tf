###############################################################################
# Redis Module — Variables
# Finding A Bed Tonight
###############################################################################

variable "tier" {
  description = "Deployment tier: standard or full"
  type        = string
  validation {
    condition     = contains(["standard", "full"], var.tier)
    error_message = "Redis module tier must be one of: standard, full"
  }
}

variable "vpc_id" {
  description = "VPC ID where the ElastiCache cluster will be created"
  type        = string
}

variable "private_subnet_ids" {
  description = "List of private subnet IDs for the cache subnet group"
  type        = list(string)
}

variable "app_security_group_id" {
  description = "Security group ID of the application (ECS tasks)"
  type        = string
}

variable "tags" {
  description = "Additional tags to apply to all resources"
  type        = map(string)
  default     = {}
}
