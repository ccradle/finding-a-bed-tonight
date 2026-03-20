###############################################################################
# Network Module — Variables
# Finding A Bed Tonight
###############################################################################

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "tier" {
  description = "Deployment tier: lite, standard, or full"
  type        = string
  validation {
    condition     = contains(["lite", "standard", "full"], var.tier)
    error_message = "tier must be one of: lite, standard, full"
  }
}

variable "aws_region" {
  description = "AWS region for the deployment"
  type        = string
  default     = "us-east-1"
}

variable "availability_zones" {
  description = "List of availability zones (must provide at least 2)"
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]
}

variable "tags" {
  description = "Additional tags to apply to all resources"
  type        = map(string)
  default     = {}
}
