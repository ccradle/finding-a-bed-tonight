###############################################################################
# Application Module — ECS Fargate, ALB, Auto-Scaling, CloudWatch
# Finding A Bed Tonight
###############################################################################

locals {
  common_tags = merge(var.tags, {
    project = "finding-a-bed-tonight"
    tier    = var.tier
    module  = "app"
  })

  cpu    = var.tier == "lite" ? 256 : 512
  memory = var.tier == "lite" ? 512 : 1024

  # Non-sensitive environment variables
  backend_environment = concat(
    [
      { name = "SPRING_PROFILES_ACTIVE", value = var.spring_profiles_active },
      { name = "SPRING_DATASOURCE_URL", value = var.db_jdbc_url },
      { name = "SPRING_DATASOURCE_USERNAME", value = var.db_username },
      { name = "FABT_CORS_ORIGINS", value = var.cors_origins },
    ],
    var.redis_host != "" ? [
      { name = "SPRING_DATA_REDIS_HOST", value = var.redis_host },
      { name = "SPRING_DATA_REDIS_PORT", value = tostring(var.redis_port) },
    ] : [],
    var.kafka_bootstrap_servers != "" ? [
      { name = "SPRING_KAFKA_BOOTSTRAP_SERVERS", value = var.kafka_bootstrap_servers },
    ] : [],
  )
}

###############################################################################
# CloudWatch Log Groups
###############################################################################

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/fabt/${var.tier}/backend"
  retention_in_days = 14

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-backend-logs"
  })
}

resource "aws_cloudwatch_log_group" "frontend" {
  name              = "/fabt/${var.tier}/frontend"
  retention_in_days = 14

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-frontend-logs"
  })
}

###############################################################################
# Application Security Group (ECS Tasks)
###############################################################################

resource "aws_security_group" "app" {
  name_prefix = "fabt-${var.tier}-app-"
  description = "Security group for FABT ECS tasks"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Backend from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  ingress {
    description     = "Frontend from ALB"
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-app-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

###############################################################################
# ALB Security Group
###############################################################################

resource "aws_security_group" "alb" {
  name_prefix = "fabt-${var.tier}-alb-"
  description = "Security group for FABT ALB"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTP from anywhere"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS from anywhere"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-alb-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

###############################################################################
# Application Load Balancer
###############################################################################

resource "aws_lb" "main" {
  name               = "fabt-${var.tier}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = var.public_subnet_ids

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-alb"
  })
}

###############################################################################
# Target Groups
###############################################################################

resource "aws_lb_target_group" "backend" {
  name        = "fabt-${var.tier}-backend"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path                = "/api/actuator/health"
    port                = "8080"
    protocol            = "HTTP"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 10
    interval            = 30
    matcher             = "200"
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-backend-tg"
  })
}

resource "aws_lb_target_group" "frontend" {
  name        = "fabt-${var.tier}-frontend"
  port        = 80
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path                = "/"
    port                = "80"
    protocol            = "HTTP"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 30
    matcher             = "200"
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-frontend-tg"
  })
}

###############################################################################
# ALB Listener & Routing Rules
###############################################################################

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = var.certificate_arn != "" ? "redirect" : "forward"

    dynamic "redirect" {
      for_each = var.certificate_arn != "" ? [1] : []
      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }

    target_group_arn = var.certificate_arn == "" ? aws_lb_target_group.frontend.arn : null
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-http-listener"
  })
}

resource "aws_lb_listener" "https" {
  count = var.certificate_arn != "" ? 1 : 0

  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.frontend.arn
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-https-listener"
  })
}

resource "aws_lb_listener_rule" "api" {
  listener_arn = var.certificate_arn != "" ? aws_lb_listener.https[0].arn : aws_lb_listener.http.arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }

  condition {
    path_pattern {
      values = ["/api/*"]
    }
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-api-rule"
  })
}

###############################################################################
# ECS Cluster
###############################################################################

resource "aws_ecs_cluster" "main" {
  name = "fabt-${var.tier}"

  setting {
    name  = "containerInsights"
    value = var.tier == "full" ? "enabled" : "disabled"
  }

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-cluster"
  })
}

###############################################################################
# IAM — ECS Task Execution Role
###############################################################################

data "aws_iam_policy_document" "ecs_assume_role" {
  statement {
    effect = "Allow"
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    actions = ["sts:AssumeRole"]
  }
}

resource "aws_iam_role" "ecs_task_execution" {
  name = "fabt-${var.tier}-ecs-execution"

  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-ecs-execution-role"
  })
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

###############################################################################
# IAM — ECS Task Role (for the application itself)
###############################################################################

resource "aws_iam_role" "ecs_task" {
  name = "fabt-${var.tier}-ecs-task"

  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-ecs-task-role"
  })
}

###############################################################################
# ECS Task Definition
###############################################################################

resource "aws_ecs_task_definition" "app" {
  family                   = "fabt-${var.tier}"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = local.cpu
  memory                   = local.memory
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name      = "backend"
      image     = var.backend_image
      essential = true

      portMappings = [
        {
          containerPort = 8080
          hostPort      = 8080
          protocol      = "tcp"
        }
      ]

      environment = [
        for env in local.backend_environment : {
          name  = env.name
          value = env.value
        }
      ]

      secrets = [
        {
          name      = "SPRING_DATASOURCE_PASSWORD"
          valueFrom = var.db_password_secret_arn
        },
        {
          name      = "FABT_JWT_SECRET"
          valueFrom = var.jwt_secret_arn
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.backend.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "backend"
        }
      }
    },
    {
      name      = "frontend"
      image     = var.frontend_image
      essential = true

      portMappings = [
        {
          containerPort = 80
          hostPort      = 80
          protocol      = "tcp"
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.frontend.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "frontend"
        }
      }
    }
  ])

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-task-def"
  })
}

###############################################################################
# ECS Service
###############################################################################

resource "aws_ecs_service" "app" {
  name            = "fabt-${var.tier}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend.arn
    container_name   = "backend"
    container_port   = 8080
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.frontend.arn
    container_name   = "frontend"
    container_port   = 80
  }

  depends_on = [
    aws_lb_listener.http,
    aws_lb_listener_rule.api,
  ]

  tags = merge(local.common_tags, {
    Name = "fabt-${var.tier}-service"
  })
}

###############################################################################
# Auto-Scaling (Full tier only: min 1, max 3)
###############################################################################

resource "aws_appautoscaling_target" "ecs" {
  count = var.tier == "full" ? 1 : 0

  max_capacity       = 3
  min_capacity       = 1
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.app.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "ecs_cpu" {
  count = var.tier == "full" ? 1 : 0

  name               = "fabt-${var.tier}-cpu-autoscaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.ecs[0].resource_id
  scalable_dimension = aws_appautoscaling_target.ecs[0].scalable_dimension
  service_namespace  = aws_appautoscaling_target.ecs[0].service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = 70.0
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}
